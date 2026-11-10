package br.com.singlehope.shanticrash.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLoggerTest {
    private static final Logger CONSOLE = Logger.getLogger("teste");

    private static List<Path> files(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.collect(Collectors.toList());
        }
    }

    @Test
    void writesAndFlushesOnClose(@TempDir Path directory) throws IOException {
        EventLogger logger = new EventLogger(directory, CONSOLE, EventLogger.Output.FILE, 1024 * 1024, 14, 128);
        logger.start();
        logger.log("evento um");
        logger.log("evento dois");
        logger.close();
        List<Path> files = files(directory);
        assertEquals(1, files.size());
        String content = new String(Files.readAllBytes(files.get(0)), StandardCharsets.UTF_8);
        assertTrue(content.contains("evento um"));
        assertTrue(content.contains("evento dois"));
    }

    @Test
    void rotatesBySizeAndStopsAtTheDailyLimit(@TempDir Path directory) throws IOException, InterruptedException {
        EventLogger logger = new EventLogger(directory, CONSOLE, EventLogger.Output.FILE, 2048, 14, 64);
        logger.start();
        String line = new String(new char[900]).replace('\0', 'x');
        for (int i = 0; i < 5000; i++) {
            logger.log(line);
            if (i % 40 == 0) {
                Thread.sleep(1);
            }
        }
        logger.close();
        List<Path> files = files(directory);
        assertTrue(files.size() > 1, "deveria rotacionar");
        assertTrue(files.size() <= 20, "limite diário de arquivos: " + files.size());
        for (Path file : files) {
            assertTrue(Files.size(file) <= 2048);
        }
    }

    /** Offering a line must never block a Netty thread, even if the disk is slow or the queue is full. */
    @Test
    void droppedInsteadOfBlockingWhenTheQueueIsFull(@TempDir Path directory) {
        EventLogger logger = new EventLogger(directory, CONSOLE, EventLogger.Output.FILE, 1024 * 1024, 14, 16);
        for (int i = 0; i < 1000; i++) {
            logger.log("x");
        }
        assertEquals(1000 - 16, logger.droppedLines());
        logger.start();
        logger.close();
    }

    @Test
    void clientDataCanNotForgeLines() {
        assertEquals("a?b?c", EventLogger.sanitize("a\nb\rc"));
        assertEquals("?cor", EventLogger.sanitize("§cor"));
        assertEquals(1024, EventLogger.sanitize(new String(new char[5000]).replace('\0', 'y')).length());
        String clean = "linha normal com acentuação";
        assertTrue(clean == EventLogger.sanitize(clean), "linhas limpas não devem ser copiadas");
    }

    @Test
    void oldFilesAreRemoved(@TempDir Path directory) throws IOException {
        Path old = directory.resolve("eventos-2000-01-01.log");
        Files.write(old, new byte[]{1});
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 40L * 86_400_000L));
        Path recent = directory.resolve("eventos-2999-01-01.log");
        Files.write(recent, new byte[]{1});
        EventLogger logger = new EventLogger(directory, CONSOLE, EventLogger.Output.FILE, 1024, 14, 16);
        logger.start();
        logger.log("gatilho");
        logger.close();
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
    }
}
