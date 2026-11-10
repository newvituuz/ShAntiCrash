package br.com.singlehope.shanticrash.logging;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes the event log on its own thread.
 *
 * <p>{@link #log} only offers a line to a bounded queue, so it never blocks a Netty thread. When the
 * queue is full the line is dropped and counted, because during an attack keeping the server responsive
 * matters more than keeping every line. Files rotate by day and size, and old ones are removed.
 */
public final class EventLogger {
    /** Where lines go. */
    public enum Output {
        FILE,
        CONSOLE,
        BOTH
    }

    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final int MAX_LINE_LENGTH = 1024;
    private static final int MAX_FILES_PER_DAY = 20;

    private final Path directory;
    private final Logger console;
    private final Output output;
    private final long maxFileBytes;
    private final int retentionDays;
    private final BlockingQueue<String> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private final Thread thread;

    private volatile boolean running = true;
    private BufferedWriter writer;
    private String currentDay;
    private int currentIndex;
    private long currentBytes;
    private long lastCleanup;

    public EventLogger(Path directory, Logger console, Output output, long maxFileBytes, int retentionDays,
                       int queueCapacity) {
        this.directory = directory;
        this.console = console;
        this.output = output;
        this.maxFileBytes = maxFileBytes;
        this.retentionDays = retentionDays;
        this.queue = new ArrayBlockingQueue<>(Math.max(16, queueCapacity));
        this.thread = new Thread(this::runLoop, "ShAntiCrash-Log");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /** Queues a line. Never blocks. */
    public void log(String line) {
        if (!queue.offer(sanitize(line))) {
            dropped.incrementAndGet();
        }
    }

    public long droppedLines() {
        return dropped.get();
    }

    /**
     * Client data can end up in a line, so control characters are replaced to prevent forging lines,
     * and the length is limited to stop one event from filling the file.
     */
    static String sanitize(String line) {
        int length = Math.min(line.length(), MAX_LINE_LENGTH);
        StringBuilder clean = null;
        for (int i = 0; i < length; i++) {
            char c = line.charAt(i);
            if (c < 0x20 || c == 0x7F || c == 0xA7) {
                if (clean == null) {
                    clean = new StringBuilder(length).append(line, 0, i);
                }
                clean.append('?');
            } else if (clean != null) {
                clean.append(c);
            }
        }
        if (clean != null) {
            return clean.toString();
        }
        return line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) : line;
    }

    /** Stops the thread after writing what is queued, waiting for at most two seconds. */
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runLoop() {
        List<String> batch = new ArrayList<>();
        while (running || !queue.isEmpty()) {
            try {
                String first = queue.poll(1L, TimeUnit.SECONDS);
                if (first == null) {
                    flush();
                    cleanupIfDue();
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, 512);
                write(batch);
                batch.clear();
            } catch (InterruptedException e) {
                // close() interrupts to wake up the poll; the loop condition decides whether to leave.
                Thread.interrupted();
            } catch (IOException e) {
                console.log(Level.WARNING, "Falha ao gravar o log de eventos: " + e.getMessage());
                batch.clear();
                closeWriter();
            }
        }
        flush();
        closeWriter();
    }

    private void write(List<String> lines) throws IOException {
        long now = System.currentTimeMillis();
        String time = timeFormat.format(new Date(now));
        for (String line : lines) {
            if (output != Output.FILE) {
                console.info(line);
            }
            if (output != Output.CONSOLE) {
                String formatted = "[" + time + "] " + line + "\n";
                byte[] bytes = formatted.getBytes(StandardCharsets.UTF_8);
                if (!prepareFile(now, bytes.length)) {
                    dropped.incrementAndGet();
                    continue;
                }
                writer.write(formatted);
                currentBytes += bytes.length;
            }
        }
        flush();
    }

    /**
     * Opens the right file for today.
     *
     * @return false once the daily limit of files is reached, which bounds disk use during an attack
     */
    private boolean prepareFile(long now, int incomingBytes) throws IOException {
        String day = dayFormat.format(new Date(now));
        if (writer != null && day.equals(currentDay) && currentBytes + incomingBytes <= maxFileBytes) {
            return true;
        }
        closeWriter();
        Files.createDirectories(directory);
        if (!day.equals(currentDay)) {
            currentDay = day;
            currentIndex = 0;
        }
        while (currentIndex < MAX_FILES_PER_DAY) {
            Path file = directory.resolve("eventos-" + day + (currentIndex == 0 ? "" : "." + currentIndex) + ".log");
            currentBytes = Files.exists(file) ? Files.size(file) : 0L;
            if (currentBytes == 0L || currentBytes + incomingBytes <= maxFileBytes) {
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                cleanupIfDue();
                return true;
            }
            currentIndex++;
        }
        return false;
    }

    private void flush() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            closeWriter();
        }
    }

    private void closeWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // Nothing else can be done with a failed log file.
        }
        writer = null;
    }

    private void cleanupIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < DAY_MILLIS || retentionDays <= 0) {
            return;
        }
        lastCleanup = now;
        if (!Files.isDirectory(directory)) {
            return;
        }
        FileTime limit = FileTime.fromMillis(now - retentionDays * DAY_MILLIS);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "eventos-*.log")) {
            for (Path file : files) {
                if (Files.getLastModifiedTime(file).compareTo(limit) < 0) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            console.log(Level.WARNING, "Falha ao remover logs antigos: " + e.getMessage());
        }
    }
}
