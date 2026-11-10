package br.com.singlehope.shanticrash.integration;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A real server running on the loopback interface, in offline mode and without compression. Accepts the
 * Minecraft EULA for this local test instance.
 */
final class TestServer {
    static final int PORT = 25599;

    private final Path serverJar;
    private final Path javaHome;
    private final Path work;
    private final List<Path> plugins;
    private final List<String> console = new ArrayList<>();
    private final AtomicReference<String> started = new AtomicReference<>();
    private BufferedWriter input;
    private Process process;

    TestServer(Path serverJar, Path javaHome, Path work, List<Path> plugins) {
        this.serverJar = serverJar;
        this.javaHome = javaHome;
        this.work = work;
        this.plugins = plugins;
    }

    Path work() {
        return work;
    }

    private void deleteFiles(Path directory, String prefix) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.collect(Collectors.toList())) {
                if (file.getFileName().toString().startsWith(prefix) && Files.isRegularFile(file)) {
                    Files.delete(file);
                }
            }
        }
    }

    void prepare() throws IOException {
        Path pluginDirectory = work.resolve("plugins");
        Files.createDirectories(pluginDirectory);
        // The previous run may have used a different set of plugins.
        deleteFiles(pluginDirectory, "");
        for (Path plugin : plugins) {
            Files.copy(plugin, pluginDirectory.resolve(plugin.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(work.resolve("eula.txt"), "eula=true\n".getBytes(StandardCharsets.UTF_8));
        String properties = String.join("\n",
                "online-mode=false",
                // 26.3 generates the file with the whitelist turned on.
                "white-list=false",
                "server-port=" + PORT,
                "server-ip=127.0.0.1",
                "network-compression-threshold=-1",
                "level-type=minecraft\\:flat",
                "generate-structures=false",
                "spawn-protection=0",
                "view-distance=3",
                "simulation-distance=3",
                "enforce-secure-profile=false",
                "spawn-monsters=false",
                "spawn-animals=false",
                "gamemode=survival",
                "max-players=500",
                "enable-query=false",
                "enable-rcon=false");
        Files.write(work.resolve("server.properties"), properties.getBytes(StandardCharsets.UTF_8));
        Files.write(work.resolve("bukkit.yml"), "settings:\n  connection-throttle: -1\n".getBytes(StandardCharsets.UTF_8));
        // Old logs would make scenarios of a previous run look like they passed.
        deleteFiles(pluginDirectory.resolve("ShAntiCrash").resolve("logs"), "");
        deleteFiles(work.resolve("world").resolve("playerdata"), "");
        Files.deleteIfExists(work.resolve("ops.json"));
    }

    void start() throws IOException, InterruptedException {
        String java = javaHome.resolve("bin").resolve(File.separatorChar == '\\' ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-Xms1G", "-Xmx2G",
                "-Dcom.mojang.eula.agree=true", "-DPaper.IgnoreJavaVersion=true", "-Dpaper.disableChannelLimit=true",
                // The positional form is the only one every version accepts: 1.8.8 and 1.12.2 reject --nogui.
                "-Djava.awt.headless=true", "-jar", serverJar.toAbsolutePath().toString(), "nogui");
        builder.directory(work.toFile());
        builder.redirectErrorStream(true);
        process = builder.start();
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (console) {
                        console.add(line);
                    }
                    if (line.contains("Done (")) {
                        started.compareAndSet(null, line);
                    }
                }
            } catch (IOException ignored) {
                // The server stopped.
            }
        }, "server-console");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(300);
        while (started.get() == null) {
            if (!process.isAlive()) {
                throw new IOException("servidor encerrou antes de iniciar");
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("servidor não iniciou em 5 minutos");
            }
            Thread.sleep(250L);
        }
    }

    String startedLine() {
        return started.get();
    }

    void command(String command) throws IOException {
        input.write(command);
        input.newLine();
        input.flush();
    }

    boolean isAlive() {
        return process.isAlive();
    }

    int consoleSize() {
        synchronized (console) {
            return console.size();
        }
    }

    List<String> consoleSince(int from) {
        synchronized (console) {
            return new ArrayList<>(console.subList(from, console.size()));
        }
    }

    /** Runs a console command and returns what it printed, without color codes. */
    List<String> commandOutput(String command, long waitMillis) throws IOException, InterruptedException {
        int before = consoleSize();
        command(command);
        Thread.sleep(waitMillis);
        List<String> clean = new ArrayList<>();
        for (String line : consoleSince(before)) {
            clean.add(line.replaceAll("§.", "").replaceAll("\u001B\\[[;\\d]*m", ""));
        }
        return clean;
    }

    /** CPU time used by the server process so far. Reflection because tests compile for Java 8. */
    long cpuMillis() {
        try {
            Method toHandle = Process.class.getMethod("toHandle");
            Object handle = toHandle.invoke(process);
            Object info = Class.forName("java.lang.ProcessHandle").getMethod("info").invoke(handle);
            Object cpu = Class.forName("java.lang.ProcessHandle$Info").getMethod("totalCpuDuration").invoke(info);
            @SuppressWarnings("unchecked")
            Optional<Duration> duration = (Optional<Duration>) cpu;
            return duration.map(Duration::toMillis).orElse(-1L);
        } catch (ReflectiveOperationException e) {
            return -1L;
        }
    }

    /** Heap in use after a full collection, in MiB. */
    long heapUsedMegabytes() {
        try {
            Method toHandle = Process.class.getMethod("toHandle");
            Object handle = toHandle.invoke(process);
            long pid = (Long) Class.forName("java.lang.ProcessHandle").getMethod("pid").invoke(handle);
            String jcmd = javaHome.resolve("bin").resolve("jcmd.exe").toString();
            Process gc = new ProcessBuilder(jcmd, Long.toString(pid), "GC.run").redirectErrorStream(true).start();
            gc.waitFor(30, TimeUnit.SECONDS);
            Process info = new ProcessBuilder(jcmd, Long.toString(pid), "GC.heap_info").redirectErrorStream(true).start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(info.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    int index = line.indexOf("used ");
                    if (index >= 0 && line.contains("heap")) {
                        String value = line.substring(index + 5).trim();
                        int end = value.indexOf('K');
                        return Long.parseLong(value.substring(0, end)) / 1024L;
                    }
                }
            }
        } catch (ReflectiveOperationException | IOException | InterruptedException | RuntimeException e) {
            return -1L;
        }
        return -1L;
    }

    void stop() throws IOException, InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        command("stop");
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            System.out.println("AVISO servidor não parou em 90 s, encerrando à força");
            process.destroyForcibly();
        }
        Thread.sleep(500L);
    }
}
