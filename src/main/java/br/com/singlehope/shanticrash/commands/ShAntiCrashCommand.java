package br.com.singlehope.shanticrash.commands;

import br.com.singlehope.shanticrash.ShAntiCrash;
import br.com.singlehope.shanticrash.alerts.AlertManager;
import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckManager;
import br.com.singlehope.shanticrash.compatibility.CompatibilityManager;
import br.com.singlehope.shanticrash.configuration.ConfigurationManager;
import br.com.singlehope.shanticrash.configuration.Messages;
import br.com.singlehope.shanticrash.alerts.DiscordNotifier;
import br.com.singlehope.shanticrash.connection.ConnectionLimiter;
import br.com.singlehope.shanticrash.metrics.PacketMetrics;
import br.com.singlehope.shanticrash.metrics.ProfileSession;
import br.com.singlehope.shanticrash.metrics.Profiler;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.decoder.OutgoingItemSanitizer;
import br.com.singlehope.shanticrash.protection.RiskLevel;
import br.com.singlehope.shanticrash.violation.ViolationManager;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.player.PlayerDataManager;
import br.com.singlehope.shanticrash.violation.CheckState;
import br.com.singlehope.shanticrash.violation.ViolationHistory;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** {@code /shanticrash} and its subcommands. Reads only plugin state, so it is safe on any thread. */
public final class ShAntiCrashCommand implements TabExecutor {
    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "help", "info", "stats", "profile", "reload", "alerts", "check", "violations", "debug", "checks", "version");
    private static final String CONSOLE = "console";

    private final ShAntiCrash plugin;
    private final CheckManager checks;
    private final PlayerDataManager players;
    private final Messages messages;
    private final ConfigurationManager configuration;
    private final CompatibilityManager compatibility;
    private final PacketMetrics metrics;
    private final Profiler profiler;
    private final ConnectionLimiter limiter;
    private final OutgoingItemSanitizer sanitizer;
    private final ViolationManager violations;

    public ShAntiCrashCommand(ShAntiCrash plugin, CheckManager checks, PlayerDataManager players, Messages messages,
                              ConfigurationManager configuration, CompatibilityManager compatibility,
                              PacketMetrics metrics, Profiler profiler, ConnectionLimiter limiter,
                              OutgoingItemSanitizer sanitizer, ViolationManager violations) {
        this.plugin = plugin;
        this.checks = checks;
        this.players = players;
        this.messages = messages;
        this.configuration = configuration;
        this.compatibility = compatibility;
        this.metrics = metrics;
        this.profiler = profiler;
        this.limiter = limiter;
        this.sanitizer = sanitizer;
        this.violations = violations;
    }

    private void send(CommandSender sender, Component component) {
        if (sender instanceof Player) {
            PlayerData data = players.byUuid(((Player) sender).getUniqueId());
            if (data != null) {
                data.handle().sendMessage(component);
                return;
            }
        }
        sender.sendMessage(Messages.legacy(component));
    }

    private void send(CommandSender sender, String key, String... pairs) {
        send(sender, messages.component(key, pairs));
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission("shanticrash.admin")) {
            return true;
        }
        if (configuration.settings().hiddenCommand) {
            send(sender, "unknown-command");
        } else {
            send(sender, "no-permission", "permission", permission);
        }
        return false;
    }

    private static boolean anyPermission(CommandSender sender) {
        return sender.hasPermission("shanticrash.admin") || sender.hasPermission("shanticrash.alerts")
                || sender.hasPermission("shanticrash.debug") || sender.hasPermission("shanticrash.reload");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help":
                help(sender);
                return true;
            case "version":
                if (allowed(sender, "shanticrash.admin")) {
                    send(sender, "version", "version", plugin.getDescription().getVersion());
                }
                return true;
            case "info":
                if (allowed(sender, "shanticrash.admin")) {
                    info(sender);
                }
                return true;
            case "stats":
                if (allowed(sender, "shanticrash.admin")) {
                    stats(sender);
                }
                return true;
            case "profile":
                if (allowed(sender, "shanticrash.debug")) {
                    profile(sender, args);
                }
                return true;
            case "reload":
                if (allowed(sender, "shanticrash.reload")) {
                    boolean ok = plugin.reloadPlugin();
                    send(sender, ok ? "reload-ok" : "reload-failed");
                }
                return true;
            case "alerts":
                if (allowed(sender, "shanticrash.alerts")) {
                    alerts(sender, args);
                }
                return true;
            case "check":
                if (allowed(sender, "shanticrash.admin")) {
                    check(sender, args);
                }
                return true;
            case "violations":
                if (allowed(sender, "shanticrash.admin")) {
                    violations(sender, args);
                }
                return true;
            case "debug":
                if (allowed(sender, "shanticrash.debug")) {
                    debug(sender, args);
                }
                return true;
            case "checks":
                if (allowed(sender, "shanticrash.admin")) {
                    listChecks(sender);
                }
                return true;
            default:
                if (configuration.settings().hiddenCommand && !anyPermission(sender)) {
                    send(sender, "unknown-command");
                } else {
                    send(sender, "invalid-arguments");
                }
                return true;
        }
    }

    private void help(CommandSender sender) {
        if (!anyPermission(sender)) {
            if (configuration.settings().hiddenCommand) {
                send(sender, "unknown-command");
            } else {
                send(sender, "no-permission", "permission", "shanticrash.admin");
            }
            return;
        }
        for (String line : messages.rawList("help")) {
            send(sender, Messages.parse(messages.apply(line)));
        }
    }

    private void info(CommandSender sender) {
        long enabled = checks.all().stream().filter(check -> check.settings().enabled).count();
        send(sender, "info.platform", "platform", compatibility.platform(), "version",
                org.bukkit.Bukkit.getBukkitVersion());
        send(sender, "info.protocol", "protocol", Integer.toString(plugin.serverProtocol()));
        send(sender, "info.checks", "enabled", Long.toString(enabled), "total", Integer.toString(checks.all().size()));
        send(sender, "info.players", "players", Integer.toString(players.size()));
        send(sender, "info.integrations",
                "via", yesNo(compatibility.hasViaVersion()),
                "viab", yesNo(compatibility.hasViaBackwards()),
                "viar", yesNo(compatibility.hasViaRewind()),
                "geyser", yesNo(compatibility.hasGeyser()),
                "floodgate", yesNo(compatibility.hasFloodgate()));
        send(sender, "info.packets",
                "processed", Long.toString(metrics.processed()),
                "cancelled", Long.toString(metrics.cancelledCount()),
                "violations", Long.toString(metrics.violations()),
                "errors", Long.toString(metrics.checkErrors()));
    }

    private void stats(CommandSender sender) {
        long now = System.nanoTime();
        double uptime = metrics.uptimeSeconds();
        send(sender, "stats.packets",
                "processed", Long.toString(metrics.processed()),
                "rate", format1(metrics.processed() / uptime),
                "cancelled", Long.toString(metrics.cancelledCount()),
                "violations", Long.toString(metrics.violations()));
        send(sender, "stats.errors",
                "errors", Long.toString(metrics.checkErrors()),
                "uninspected", Long.toString(metrics.uninspectedPackets()),
                "rejected", Long.toString(metrics.rejectedConnections()));
        int[] levels = new int[RiskLevel.values().length];
        long memory = 0L;
        for (PlayerData data : players.all()) {
            levels[data.risk().level(violations.risk(), now).ordinal()]++;
            memory += data.approximateBytes();
        }
        send(sender, "stats.connections",
                "connections", Integer.toString(players.size()),
                "addresses", Integer.toString(limiter.trackedAddresses()),
                "memory", Long.toString(memory / 1024L));
        send(sender, "stats.risk",
                "low", Integer.toString(levels[RiskLevel.LOW.ordinal()]),
                "moderate", Integer.toString(levels[RiskLevel.MODERATE.ordinal()]),
                "high", Integer.toString(levels[RiskLevel.HIGH.ordinal()]),
                "critical", Integer.toString(levels[RiskLevel.CRITICAL.ordinal()]));
        send(sender, "stats.mode",
                "mode", configuration.settings().mode.name(),
                "profiles", Integer.toString(profiler.activeSessions()),
                "sanitized", Long.toString(sanitizer.sanitizedCount()));
        DiscordNotifier discord = plugin.discord();
        if (discord != null) {
            send(sender, "stats.discord",
                    "sent", Long.toString(discord.sent()),
                    "queued", Integer.toString(discord.queued()),
                    "dropped", Long.toString(discord.dropped()),
                    "failed", Long.toString(discord.failed()));
        }
        if (configuration.settings().packetMonitoring) {
            List<PacketKind> kinds = new ArrayList<>(Arrays.asList(PacketKind.values()));
            kinds.sort((a, b) -> Long.compare(metrics.packets(b), metrics.packets(a)));
            for (PacketKind kind : kinds.subList(0, Math.min(5, kinds.size()))) {
                if (metrics.packets(kind) == 0L) {
                    break;
                }
                send(sender, "stats.kind", "kind", kind.name(),
                        "count", Long.toString(metrics.packets(kind)),
                        "avg", Long.toString(metrics.averageNanos(kind) / 1000L),
                        "max", Long.toString(metrics.maxNanos(kind) / 1000L));
            }
        }
    }

    private void profile(CommandSender sender, String[] args) {
        long now = System.nanoTime();
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            List<ProfileSession> stopped = profiler.stopAll(now);
            if (stopped.isEmpty()) {
                send(sender, "profile.none");
                return;
            }
            for (ProfileSession session : stopped) {
                report(sender, session, now);
            }
            return;
        }
        PlayerData data = target(sender, args);
        if (data == null) {
            return;
        }
        Object owner = sender instanceof Player ? ((Player) sender).getUniqueId() : CONSOLE;
        ProfileSession session = profiler.start(data, owner, checks.all().size(), now);
        if (session == null) {
            send(sender, "profile.limit", "max", Integer.toString(profiler.settings().maxSessions));
            return;
        }
        send(sender, "profile.started", "player", data.handle().name(),
                "seconds", Long.toString(profiler.settings().durationNanos / 1_000_000_000L));
    }

    public void deliverProfile(ProfileSession session) {
        long now = System.nanoTime();
        Object owner = session.owner();
        if (owner instanceof UUID) {
            PlayerData data = players.byUuid((UUID) owner);
            if (data != null) {
                for (Component line : reportLines(session, now)) {
                    data.handle().sendMessage(line);
                }
                return;
            }
        }
        for (Component line : reportLines(session, now)) {
            plugin.getLogger().info(Messages.plain(line));
        }
    }

    private void report(CommandSender sender, ProfileSession session, long now) {
        for (Component line : reportLines(session, now)) {
            send(sender, line);
        }
    }

    private List<Component> reportLines(ProfileSession session, long now) {
        List<Component> lines = new ArrayList<>();
        double seconds = session.seconds(now);
        long total = session.totalPackets();
        lines.add(messages.component("profile.header", "player", session.target(), "seconds", format1(seconds)));
        lines.add(messages.component("profile.rates",
                "pps", format1(total / seconds),
                "bps", format1(session.bytes() / seconds),
                "packets", Long.toString(total),
                "cancelled", Long.toString(session.cancelled())));
        lines.add(messages.component("profile.latency",
                "p50", micros(session.percentileNanos(0.50D)),
                "p95", micros(session.percentileNanos(0.95D)),
                "p99", micros(session.percentileNanos(0.99D))));
        List<PacketKind> kinds = new ArrayList<>(Arrays.asList(PacketKind.values()));
        kinds.sort((a, b) -> Long.compare(session.packets(b), session.packets(a)));
        StringBuilder distribution = new StringBuilder();
        for (PacketKind kind : kinds.subList(0, Math.min(6, kinds.size()))) {
            long count = session.packets(kind);
            if (count == 0L) {
                break;
            }
            if (distribution.length() > 0) {
                distribution.append(", ");
            }
            distribution.append(kind.name()).append('=').append(count);
        }
        lines.add(messages.component("profile.kinds", "list", distribution.length() == 0 ? "-" : distribution.toString()));
        List<Check> all = checks.all();
        List<Check> ran = new ArrayList<>();
        long flags = 0L;
        for (Check check : all) {
            if (check.index() < session.checks() && session.checkRuns(check.index()) > 0L) {
                ran.add(check);
            }
            if (check.index() < session.checks()) {
                flags += session.checkFlags(check.index());
            }
        }
        ran.sort((a, b) -> Long.compare(session.checkNanos(b.index()), session.checkNanos(a.index())));
        lines.add(messages.component("profile.checks", "executed", Integer.toString(ran.size()),
                "flags", Long.toString(flags)));
        for (Check check : ran.subList(0, Math.min(5, ran.size()))) {
            long runs = session.checkRuns(check.index());
            long nanos = session.checkNanos(check.index());
            lines.add(messages.component("profile.check", "check", check.id(),
                    "runs", Long.toString(runs),
                    "avg", Long.toString(runs == 0L ? 0L : nanos / runs),
                    "flags", Long.toString(session.checkFlags(check.index()))));
        }
        PlayerData data = players.byName(session.target());
        lines.add(messages.component("profile.memory", "bytes",
                data == null ? "-" : Long.toString(data.approximateBytes())));
        return lines;
    }

    private static String micros(long nanos) {
        return nanos == Long.MAX_VALUE ? ">4611686s" : format1(nanos / 1000.0D);
    }

    private static String format1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String yesNo(boolean value) {
        return messages.raw(value ? "value-yes" : "value-no");
    }

    private void alerts(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, "players-only");
            return;
        }
        PlayerData data = players.byUuid(((Player) sender).getUniqueId());
        if (data == null) {
            send(sender, "player-not-found", "player", sender.getName());
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("categoria")) {
            String category = args[2].toLowerCase(Locale.ROOT);
            if (data.mutedCategories().remove(category)) {
                send(sender, "alerts.category-enabled", "category", category);
            } else {
                data.mutedCategories().add(category);
                send(sender, "alerts.category-disabled", "category", category);
            }
            return;
        }
        boolean target;
        if (args.length >= 2 && args[1].equalsIgnoreCase("on")) {
            target = true;
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("off")) {
            target = false;
        } else {
            target = !data.isAlertsEnabled();
        }
        data.alertsEnabled(target);
        send(sender, target ? "alerts.enabled" : "alerts.disabled");
    }

    private PlayerData target(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "invalid-arguments");
            return null;
        }
        PlayerData data = players.byName(args[1]);
        if (data == null) {
            send(sender, "player-not-found", "player", args[1]);
        }
        return data;
    }

    private void check(CommandSender sender, String[] args) {
        PlayerData data = target(sender, args);
        if (data == null) {
            return;
        }
        send(sender, "check.header", "player", data.handle().name());
        send(sender, "check.client",
                "version", data.handle().clientVersion(),
                "protocol", Integer.toString(data.handle().clientProtocol()));
        send(sender, "check.state",
                "bedrock", yesNo(data.isBedrock()),
                "creative", yesNo(data.isCreative()),
                "printer", yesNo(data.isPrinterMode()),
                "bypass", yesNo(data.isBypass()),
                "multiplier", AlertManager.format(data.floodMultiplier()));
        send(sender, "check.connected", "time", formatTime(data.connectedAtMillis()));
        long now = System.nanoTime();
        send(sender, "check.risk",
                "level", data.risk().level(violations.risk(), now).name(),
                "score", format1(data.risk().decayed(violations.risk(), now)),
                "factor", format1(data.riskRateFactor(now)));
    }

    private void violations(CommandSender sender, String[] args) {
        PlayerData data = target(sender, args);
        if (data == null) {
            return;
        }
        send(sender, "violations.header", "player", data.handle().name());
        long now = System.nanoTime();
        boolean any = false;
        for (Check check : checks.all()) {
            CheckState state = data.peekState(check.index());
            if (state == null || state.flags() == 0) {
                continue;
            }
            any = true;
            send(sender, "violations.entry",
                    "check", check.id(),
                    "vl", AlertManager.format(state.vlAfterDecay(check.settings(), now)),
                    "max-vl", AlertManager.format(check.settings().punishAt),
                    "flags", Integer.toString(state.flags()));
        }
        if (!any) {
            send(sender, "violations.none");
            return;
        }
        List<ViolationHistory.Entry> history = data.history().snapshot();
        int from = Math.max(0, history.size() - 5);
        for (ViolationHistory.Entry entry : history.subList(from, history.size())) {
            send(sender, "violations.history",
                    "time", formatTime(entry.timeMillis),
                    "check", entry.check,
                    "infos", entry.info);
        }
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(millis));
    }

    private void debug(CommandSender sender, String[] args) {
        PlayerData data = target(sender, args);
        if (data == null) {
            return;
        }
        data.debug(!data.isDebug());
        send(sender, data.isDebug() ? "debug.enabled" : "debug.disabled", "player", data.handle().name());
    }

    private void listChecks(CommandSender sender) {
        Set<String> categories = new LinkedHashSet<>();
        for (Check check : checks.all()) {
            categories.add(check.category());
        }
        for (String category : categories) {
            StringBuilder list = new StringBuilder();
            for (Check check : checks.all()) {
                if (check.category().equals(category)) {
                    list.append(check.settings().enabled ? "<green>" : "<red>")
                            .append(check.type())
                            .append("</")
                            .append(check.settings().enabled ? "green" : "red")
                            .append("> ");
                }
            }
            send(sender, Messages.parse(messages.raw("checks.category")
                    .replace("%prefix%", messages.raw("prefix"))
                    .replace("%category%", category.toUpperCase(Locale.ROOT))
                    .replace("%list%", list.toString().trim())));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!anyPermission(sender)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "alerts":
                    return filter(Arrays.asList("on", "off", "categoria"), args[1]);
                case "profile":
                    List<String> options = new ArrayList<>(players.namesStartingWith(args[1]));
                    if ("stop".startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        options.add(0, "stop");
                    }
                    return options;
                case "check":
                case "violations":
                case "debug":
                    return players.namesStartingWith(args[1]);
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("alerts") && args[1].equalsIgnoreCase("categoria")) {
            Set<String> categories = new LinkedHashSet<>();
            for (Check check : checks.all()) {
                categories.add(check.category());
            }
            return filter(new ArrayList<>(categories), args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
