package br.com.singlehope.shanticrash.integration;

import br.com.singlehope.shanticrash.support.TestPacketEvents;
import br.com.singlehope.shanticrash.support.Wire;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Starts a real server with the built jar and plays every scenario against it on the loopback interface.
 *
 * <p>A scenario passes when the expected check appears in the plugin's own event log. Scenarios with no
 * expected check pass if the connection survives and nothing was logged for the bot. The run only passes
 * if the server is still alive at the end and the connection map returned to zero.
 *
 * <p>Arguments: {@code <server jar> <java home> <work dir> <plugin jar> <protocol name> [extra plugin jars...]}.
 * The system property {@code bot.version} makes the bots use a different protocol, to test ViaVersion, and
 * {@code bot.scenarios} restricts which scenarios run.
 */
public final class IntegrationRunner {
    private static final int PORT = TestServer.PORT;

    private final TestServer server;
    private final ClientVersion client;
    private final Set<String> onlyScenarios;
    private final AtomicInteger botCounter = new AtomicInteger();
    private final Path eventLogs;

    private IntegrationRunner(String[] args) {
        List<Path> plugins = new ArrayList<>();
        plugins.add(Paths.get(args[3]));
        for (int i = 5; i < args.length; i++) {
            plugins.add(Paths.get(args[i]));
        }
        this.server = new TestServer(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]), plugins);
        String botVersion = System.getProperty("bot.version", args[4]);
        this.client = ServerVersion.valueOf(botVersion).toClientVersion();
        String scenarios = System.getProperty("bot.scenarios", "");
        this.onlyScenarios = scenarios.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(scenarios.split(",")));
        this.eventLogs = Paths.get(args[2]).resolve("plugins").resolve("ShAntiCrash").resolve("logs");
    }

    public static void main(String[] args) throws Exception {
        TestPacketEvents.install(ServerVersion.valueOf(System.getProperty("bot.version", args[4])));
        int failures = new IntegrationRunner(args).run();
        System.exit(failures == 0 ? 0 : 1);
    }

    private List<String> logLines() throws IOException {
        if (!Files.isDirectory(eventLogs)) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>();
        try (Stream<Path> files = Files.list(eventLogs)) {
            for (Path file : files.collect(Collectors.toList())) {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        }
        return lines;
    }

    private boolean logged(String bot, String check) throws IOException {
        for (String line : logLines()) {
            if (line.contains(bot + " ") && line.contains(" " + check + " ")) {
                return true;
            }
        }
        return false;
    }

    private String loggedFor(String bot) throws IOException {
        StringBuilder found = new StringBuilder();
        for (String line : logLines()) {
            if (line.contains(bot + " ")) {
                int start = Math.min(line.indexOf(']') + 2, line.length());
                found.append(line, start, Math.min(line.length(), start + 120)).append(" | ");
            }
        }
        return found.toString();
    }

    private String attempt(Scenarios.Scenario scenario) {
        try {
            return runScenario(scenario);
        } catch (Exception e) {
            return "exceção no teste: " + e;
        }
    }

    private static boolean nothingHappened(String error) {
        return error.startsWith("esperava ") && error.endsWith("registrado: ");
    }

    private String runScenario(Scenarios.Scenario scenario) throws Exception {
        String name = "bot" + botCounter.incrementAndGet();
        try (ProtocolBot bot = new ProtocolBot(client, name)) {
            bot.join(PORT);
            if (scenario.creative) {
                server.command("gamemode creative " + name);
                Thread.sleep(3_400L);
            }
            scenario.attack.run(bot);
            if (scenario.expectedCheck.isEmpty()) {
                Thread.sleep(400L);
                if (bot.isClosed()) {
                    return "conexão encerrada: " + bot.disconnectReason() + " " + loggedFor(name);
                }
                String events = loggedFor(name);
                return events.isEmpty() ? null : "detecção indevida: " + events;
            }
            long deadline = System.currentTimeMillis() + 4_000L;
            while (System.currentTimeMillis() < deadline) {
                if (logged(name, scenario.expectedCheck)) {
                    return null;
                }
                Thread.sleep(100L);
            }
            return "esperava " + scenario.expectedCheck + "; registrado: " + loggedFor(name)
                    + (bot.isClosed() ? " desconectado: " + bot.disconnectReason() : "");
        }
    }

    private int run() throws Exception {
        server.prepare();
        server.start();
        System.out.println("SERVIDOR " + server.startedLine());
        int failures = 0;
        try {
            for (Scenarios.Scenario scenario : new Scenarios(client).all()) {
                if (!onlyScenarios.isEmpty() && !onlyScenarios.contains(scenario.name)) {
                    continue;
                }
                String error = attempt(scenario);
                boolean retried = false;
                if (error != null && nothingHappened(error)) {
                    retried = true;
                    error = attempt(scenario);
                }
                if (error == null) {
                    System.out.println("PASS " + scenario.name + (retried ? " (2ª tentativa: na 1ª nada foi registrado e a conexão seguiu aberta)" : ""));
                } else {
                    System.out.println("FAIL " + scenario.name + " -> " + error);
                    failures++;
                }
            }
            failures += robustness();
            failures += alerts();
            failures += reload();
            failures += connectionLimit();
            failures += sanitizer();
            failures += diagnostics();
            failures += finalChecks();
        } finally {
            if (!server.isAlive()) {
                System.out.println("FAIL parada -> servidor já estava morto");
                failures++;
            }
            server.stop();
        }
        failures += scanConsole();
        System.out.println(failures == 0 ? "RESULTADO OK" : "RESULTADO FALHOU " + failures);
        return failures;
    }

    /** Connections that die at every stage: half frames, garbage and abrupt closes, many at the same time. */
    private int robustness() throws Exception {
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int seed = t;
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    String name = "des" + seed + "x" + i;
                    try (ProtocolBot bot = new ProtocolBot(client, name)) {
                        bot.connect(PORT);
                        switch (i % 4) {
                            case 0:
                                bot.sendRaw(new byte[]{(byte) 0xFF, (byte) 0xFF, 0x7F, 1, 2});
                                break;
                            case 1:
                                bot.sendRaw(new byte[]{5, 0x00, 1});
                                break;
                            case 2:
                                bot.send(0x00, new byte[]{1, 2, 3});
                                bot.send(0x7F, new byte[64]);
                                break;
                            default:
                                bot.startReader();
                                Thread.sleep(20L);
                                break;
                        }
                    } catch (IOException e) {
                        // An abrupt reset is one of the things being tested.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "robustez-" + t);
            thread.start();
            threads.add(thread);
        }
        for (Thread thread : threads) {
            thread.join(60_000L);
        }
        Thread.sleep(1_500L);
        if (!server.isAlive()) {
            System.out.println("FAIL robustez -> servidor caiu");
            return 1;
        }
        System.out.println("PASS robustez");
        return 0;
    }

    /** An operator gets the alert in chat, with the Portuguese text. */
    private int alerts() throws Exception {
        try (ProtocolBot first = new ProtocolBot(client, "adm")) {
            first.join(PORT);
            server.command("op adm");
            Thread.sleep(800L);
        }
        Thread.sleep(1_000L);
        try (ProtocolBot admin = new ProtocolBot(client, "adm"); ProtocolBot attacker = new ProtocolBot(client, "atk")) {
            admin.watchFor("falhou em");
            admin.join(PORT);
            attacker.join(PORT);
            attacker.send(attacker.playId(PacketType.Play.Client.PLAYER_ROTATION),
                    br.com.singlehope.shanticrash.support.Wire.create().f(0).f(900F).b(1).toBytes());
            if (admin.awaitWatched(4_000L)) {
                System.out.println("PASS alertas");
                return 0;
            }
            System.out.println("FAIL alertas -> mensagem não chegou");
            return 1;
        }
    }

    /** Reload applies a valid file and refuses an invalid one, keeping what was running. */
    private int reload() throws Exception {
        int failures = 0;
        List<String> output = server.commandOutput("shanticrash reload", 1_500L);
        if (output.stream().anyMatch(line -> line.contains("recarregada"))) {
            System.out.println("PASS reload");
        } else {
            System.out.println("FAIL reload -> " + output);
            failures++;
        }
        Path config = server.work().resolve("plugins").resolve("ShAntiCrash").resolve("config.yml");
        byte[] original = Files.readAllBytes(config);
        Files.write(config, "\ngeneral: [quebrado\n  : :\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        output = server.commandOutput("shanticrash reload", 1_500L);
        Files.write(config, original);
        if (output.stream().anyMatch(line -> line.contains("anterior foi mantida"))) {
            System.out.println("PASS reload-invalido");
        } else {
            System.out.println("FAIL reload-invalido -> " + output);
            failures++;
        }
        return failures;
    }

    private Path pluginConfig() {
        return server.work().resolve("plugins").resolve("ShAntiCrash").resolve("config.yml");
    }

    private byte[] rewriteConfig(java.util.function.UnaryOperator<String> change) throws Exception {
        Path config = pluginConfig();
        byte[] original = Files.readAllBytes(config);
        Files.write(config, change.apply(new String(original, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        server.commandOutput("shanticrash reload", 1_500L);
        return original;
    }

    private void restoreConfig(byte[] original) throws Exception {
        Files.write(pluginConfig(), original);
        server.commandOutput("shanticrash reload", 1_500L);
    }

    private int connectionLimit() throws Exception {
        byte[] original = rewriteConfig(text -> text
                .replace("max-per-address: 10", "max-per-address: 3")
                .replace("exempt-loopback: true", "exempt-loopback: false"));
        List<ProtocolBot> open = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                ProtocolBot bot = new ProtocolBot(client, "lim" + i);
                open.add(bot);
                bot.join(PORT);
            }
            ProtocolBot extra = new ProtocolBot(client, "lim3");
            open.add(extra);
            boolean refused = false;
            try {
                extra.connect(PORT);
                extra.startReader();
                Thread.sleep(1_500L);
            } catch (IOException e) {
                refused = true;
            }
            boolean firstOpen = !open.get(0).isClosed();
            if ((refused || extra.isClosed()) && firstOpen) {
                System.out.println("PASS limite-por-endereco");
                return 0;
            }
            System.out.println("FAIL limite-por-endereco -> extra fechada: " + extra.isClosed() + ", primeira aberta: " + firstOpen);
            return 1;
        } finally {
            for (ProtocolBot bot : open) {
                bot.close();
            }
            Thread.sleep(800L);
            restoreConfig(original);
        }
    }

    private int sanitizer() throws Exception {
        if (client.getProtocolVersion() < 766) {
            return 0;
        }
        byte[] original = rewriteConfig(text -> text
                .replaceFirst("(outgoing-sanitizer:[\\s\\S]*?enabled: )false", "$1true")
                .replace("max-name-chars: 2048", "max-name-chars: 16"));
        try (ProtocolBot bot = new ProtocolBot(client, "san")) {
            bot.join(PORT);
            String name = Wire.repeat('a', 40);
            String component = client.getProtocolVersion() >= 770 ? "custom_name=\"" + name + "\"" : "custom_name='\"" + name + "\"'";
            server.command("give san minecraft:diamond[" + component + "] 1");
            Thread.sleep(1_200L);
            String sanitized = null;
            for (String line : server.commandOutput("shanticrash stats", 1_000L)) {
                int index = line.indexOf("sanitizados:");
                if (index >= 0) {
                    sanitized = line.substring(index + "sanitizados:".length()).trim();
                }
            }
            boolean alive = !bot.isClosed();
            if (alive && sanitized != null && !sanitized.startsWith("0")) {
                System.out.println("PASS sanitizador-saida itens: " + sanitized);
                return 0;
            }
            System.out.println("FAIL sanitizador-saida -> conectado: " + alive + ", contador: " + sanitized);
            return 1;
        } finally {
            restoreConfig(original);
        }
    }

    private int diagnostics() throws Exception {
        int failures = 0;
        List<String> stats = server.commandOutput("shanticrash stats", 1_000L);
        if (stats.stream().anyMatch(line -> line.contains("Pacotes:")) && stats.stream().anyMatch(line -> line.contains("Risco:"))) {
            System.out.println("PASS comando-stats");
        } else {
            System.out.println("FAIL comando-stats -> " + stats);
            failures++;
        }
        try (ProtocolBot bot = new ProtocolBot(client, "perf")) {
            bot.join(PORT);
            List<String> started = server.commandOutput("shanticrash profile perf", 800L);
            for (int i = 0; i < 20; i++) {
                bot.send(bot.playId(PacketType.Play.Client.PLAYER_ROTATION), Wire.create().f(i).f(0).b(1).toBytes());
                Thread.sleep(50L);
            }
            List<String> report = server.commandOutput("shanticrash profile stop", 1_000L);
            boolean ok = started.stream().anyMatch(line -> line.contains("iniciado"))
                    && report.stream().anyMatch(line -> line.contains("p95"))
                    && report.stream().anyMatch(line -> line.contains("MOVE_ROTATION"));
            if (ok) {
                System.out.println("PASS comando-profile");
            } else {
                System.out.println("FAIL comando-profile -> " + started + " " + report);
                failures++;
            }
        }
        return failures;
    }

    private int finalChecks() throws Exception {
        int failures = 0;
        // A new player must still be able to get in after everything that happened.
        try (ProtocolBot bot = new ProtocolBot(client, "final")) {
            bot.join(PORT);
            Thread.sleep(500L);
            if (bot.isClosed()) {
                System.out.println("FAIL servidor-vivo -> " + bot.disconnectReason());
                failures++;
            } else {
                System.out.println("PASS servidor-vivo");
            }
        } catch (Exception e) {
            System.out.println("FAIL servidor-vivo -> " + e);
            failures++;
        }
        Thread.sleep(1_500L);
        String monitored = null;
        for (String line : server.commandOutput("shanticrash info", 1_000L)) {
            int index = line.indexOf("monitoradas:");
            if (index >= 0) {
                monitored = line.substring(index).trim();
            }
        }
        if (monitored == null) {
            System.out.println("FAIL comando-info -> sem resposta");
            failures++;
        } else if (monitored.endsWith(": 0")) {
            System.out.println("PASS sem-vazamento " + monitored);
        } else {
            System.out.println("FAIL sem-vazamento -> " + monitored);
            failures++;
        }
        return failures;
    }

    /** Anything of ours that went wrong on the server side, including what a scenario did not reveal. */
    private int scanConsole() {
        List<String> forbidden = Arrays.asList("NoClassDefFoundError", "ClassNotFoundException", "NoSuchMethodError",
                "NoSuchFieldError", "IncompatibleClassChangeError", "Erro isolado", "Falha ao iniciar",
                "Could not pass event", "Could not load 'plugins", "Error occurred while enabling ShAntiCrash");
        int failures = 0;
        boolean enabled = false;
        for (String line : server.consoleSince(0)) {
            if (line.contains("[ShAntiCrash]") && line.contains("Ativo em")) {
                enabled = true;
                System.out.println("PLUGIN " + line.substring(line.indexOf("Ativo em")));
            }
            for (String word : forbidden) {
                if (line.contains(word)) {
                    System.out.println("FAIL console -> " + line);
                    failures++;
                    break;
                }
            }
        }
        if (!enabled) {
            System.out.println("FAIL plugin não iniciou");
            failures++;
        }
        return failures;
    }
}
