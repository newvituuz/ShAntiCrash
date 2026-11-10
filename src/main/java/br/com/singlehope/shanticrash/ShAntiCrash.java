package br.com.singlehope.shanticrash;

import br.com.singlehope.shanticrash.alerts.AlertManager;
import br.com.singlehope.shanticrash.alerts.DiscordNotifier;
import br.com.singlehope.shanticrash.api.event.EventDispatcher;
import br.com.singlehope.shanticrash.connection.ConnectionLimiter;
import br.com.singlehope.shanticrash.connection.ProxyDetector;
import br.com.singlehope.shanticrash.metrics.ProfileSession;
import br.com.singlehope.shanticrash.metrics.Profiler;
import br.com.singlehope.shanticrash.packet.decoder.OutgoingItemSanitizer;
import br.com.singlehope.shanticrash.api.ShAntiCrashAPI;
import br.com.singlehope.shanticrash.checks.CheckManager;
import br.com.singlehope.shanticrash.commands.ShAntiCrashCommand;
import br.com.singlehope.shanticrash.compatibility.CompatibilityManager;
import br.com.singlehope.shanticrash.configuration.ConfigurationManager;
import br.com.singlehope.shanticrash.configuration.Messages;
import br.com.singlehope.shanticrash.configuration.PluginSettings;
import br.com.singlehope.shanticrash.item.ItemAnalyzer;
import br.com.singlehope.shanticrash.logging.EventLogger;
import br.com.singlehope.shanticrash.mechanics.MechanicsListener;
import br.com.singlehope.shanticrash.metrics.NettyWatchdog;
import br.com.singlehope.shanticrash.metrics.PacketMetrics;
import br.com.singlehope.shanticrash.packet.PacketManager;
import br.com.singlehope.shanticrash.packet.ServerProtocol;
import br.com.singlehope.shanticrash.packet.decoder.PacketEventsCreativeRewriter;
import br.com.singlehope.shanticrash.packet.decoder.PacketEventsItemReader;
import br.com.singlehope.shanticrash.player.PlayerDataManager;
import br.com.singlehope.shanticrash.player.PlayerListener;
import br.com.singlehope.shanticrash.punishment.PunishmentManager;
import br.com.singlehope.shanticrash.scheduler.SchedulerManager;
import br.com.singlehope.shanticrash.violation.ViolationManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Plugin entry point: builds the managers in dependency order and tears them down in reverse.
 *
 * <p>PacketEvents needs {@code load} during plugin load, before the server starts accepting
 * connections, or the first connections would go through uninspected.
 */
public final class ShAntiCrash extends JavaPlugin {
    private ConfigurationManager configuration;
    private Messages messages;
    private SchedulerManager scheduler;
    private CompatibilityManager compatibility;
    private PacketMetrics metrics;
    private PlayerDataManager players;
    private AlertManager alerts;
    private CheckManager checks;
    private PacketManager packets;
    private PlayerListener playerListener;
    private EventLogger eventLogger;
    private NettyWatchdog watchdog;
    private ViolationManager violations;
    private PunishmentManager punishments;
    private final ConnectionLimiter limiter = new ConnectionLimiter();
    private final OutgoingItemSanitizer sanitizer = new OutgoingItemSanitizer();
    private final Profiler profiler = new Profiler();
    private EventDispatcher events;
    private DiscordNotifier discord;
    private ShAntiCrashCommand command;
    private String proxy;
    private boolean started;

    @Override
    public void onLoad() {
        PacketEventsSettings settings = new PacketEventsSettings()
                // Reading a packet must never rewrite it. Only the decoding guard asks for that.
                .reEncodeByDefault(false)
                .checkForUpdates(false)
                .bStats(false);
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this, settings));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        try {
            start();
            started = true;
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.SEVERE, "Falha ao iniciar. O ShAntiCrash será desativado.", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void start() {
        configuration = new ConfigurationManager(this);
        configuration.load();
        PluginSettings settings = configuration.settings();
        messages = new Messages(this, configuration);
        messages.load(settings.language);

        scheduler = new SchedulerManager(this);
        compatibility = new CompatibilityManager(getServer().getPluginManager(), getLogger());
        metrics = new PacketMetrics();
        alerts = new AlertManager(configuration, messages, getLogger());
        punishments = new PunishmentManager(configuration, messages, alerts, scheduler, metrics, getLogger());
        events = new EventDispatcher(getLogger());
        punishments.events(events);
        proxy = ProxyDetector.detect(new File(System.getProperty("user.dir", ".")));

        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
        ServerProtocol protocol = ServerProtocol.of(version.getProtocolVersion());
        if (protocol.componentItems()) {
            PacketEventsItemReader.warmUp(version.toClientVersion());
        }

        violations = new ViolationManager(punishments);
        checks = new CheckManager(new ItemAnalyzer(new PacketEventsItemReader()), new PacketEventsCreativeRewriter(), violations);
        configuration.applyToChecks(checks);
        violations.enabled(configuration.raw().getBoolean("violations.enabled", true));

        checks.printer.listener((data, active) -> alerts.printerNotice(messages.component(
                active ? "printer.join" : "printer.leave", "player", data.handle().name())));

        metrics.checks(checks.all().size());
        players = new PlayerDataManager(checks.all().size(), settings.historySize, settings.maxPlayerData);
        players.removalListener(data -> {
            InetAddress address = data.limitedAddress();
            if (address != null) {
                data.limitedAddress(null);
                limiter.release(address);
            }
        });
        packets = new PacketManager(checks, players, configuration, metrics, getLogger(), protocol, limiter, sanitizer);
        packets.events(events);
        applyRuntime(settings);
        startDiagnostics(settings);
        PacketManager.register(packets);

        playerListener = new PlayerListener(players, alerts, configuration, compatibility, scheduler);
        getServer().getPluginManager().registerEvents(playerListener, this);
        getServer().getPluginManager().registerEvents(new MechanicsListener(configuration), this);

        command = new ShAntiCrashCommand(this, checks, players, messages, configuration, compatibility, metrics,
                profiler, limiter, sanitizer, violations);
        PluginCommand pluginCommand = getCommand("shanticrash");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        ShAntiCrashAPI.install(checks, players, violations, () -> configuration.settings().mode.name(),
                getDescription().getVersion());
        PacketEvents.getAPI().init();
        playerListener.synchronizeOnline();

        scheduler.asyncRepeating(players::sweep, settings.cleanupIntervalSeconds, settings.cleanupIntervalSeconds, TimeUnit.SECONDS);
        scheduler.asyncRepeating(this::expireProfiles, 1L, 1L, TimeUnit.SECONDS);

        getLogger().info("Ativo em " + compatibility.platform() + " " + version.getReleaseName()
                + " (protocolo " + version.getProtocolVersion() + ") com " + checks.all().size() + " checks, modo "
                + settings.mode + ".");
        if (proxy != null) {
            getLogger().info("Proxy detectado: " + proxy + ". " + (limiter.settings().active()
                    ? "O limite por endereço vale só para conexões fora de connection.trusted-proxies."
                    : "O limite de conexões por endereço fica desligado até connection.trusted-proxies ser configurado."));
        }
        if (isUnknownVersion(version)) {
            getLogger().warning("Versão do servidor mais nova que a suportada pelo PacketEvents. "
                    + "Falhas de decodificação serão registradas em netty.decode-failure.");
        }
    }

    private void expireProfiles() {
        ShAntiCrashCommand active = command;
        for (ProfileSession session : profiler.expire(System.nanoTime())) {
            if (active != null) {
                active.deliverProfile(session);
            }
        }
    }

    private void applyRuntime(PluginSettings settings) {
        violations.risk(settings.risk);
        Set<InetAddress> trusted = new HashSet<>();
        for (String entry : settings.trustedProxies) {
            InetAddress address = literalAddress(entry);
            if (address == null) {
                getLogger().warning("connection.trusted-proxies aceita só endereços IP: " + entry);
            } else {
                trusted.add(address);
            }
        }
        limiter.settings(new ConnectionLimiter.Settings(settings.maxConnectionsPerAddress, settings.exemptLoopback,
                trusted, proxy));
        sanitizer.settings(settings.sanitizer);
        profiler.settings(settings.profiler);
        DiscordNotifier previous = discord;
        if (previous != null) {
            previous.close();
        }
        if (settings.discordEnabled) {
            discord = new DiscordNotifier(new DiscordNotifier.Settings(true, settings.discordUrl,
                    settings.discordTimeoutMillis, settings.discordMaxPerMinute, settings.discordGroupMillis,
                    settings.discordQueueCapacity, settings.discordUsername), null, getLogger());
            discord.start();
        } else {
            discord = null;
        }
        punishments.discord(discord);
    }

    static InetAddress literalAddress(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || !trimmed.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(trimmed);
        } catch (IOException e) {
            return null;
        }
    }

    public DiscordNotifier discord() {
        return discord;
    }

    private static boolean isUnknownVersion(ServerVersion version) {
        return version == ServerVersion.ERROR;
    }

    private void startDiagnostics(PluginSettings settings) {
        if (settings.loggingEnabled) {
            eventLogger = new EventLogger(getDataFolder().toPath().resolve("logs"), getLogger(), settings.logOutput,
                    settings.logMaxFileBytes, settings.logRetentionDays, settings.logQueueCapacity);
            eventLogger.start();
        } else {
            eventLogger = null;
        }
        alerts.logger(eventLogger);
        if (settings.watchdogEnabled && eventLogger != null) {
            watchdog = new NettyWatchdog(settings.watchdogMaxMillis, settings.watchdogCheckMillis,
                    settings.watchdogCooldownMillis, eventLogger);
            watchdog.start();
        } else {
            watchdog = null;
        }
        packets.refresh(watchdog, eventLogger);
    }

    private void stopDiagnostics() {
        if (watchdog != null) {
            watchdog.stop();
            watchdog = null;
        }
        if (eventLogger != null) {
            alerts.logger(null);
            eventLogger.close();
            eventLogger = null;
        }
    }

    /** Applies config.yml again. Connection state and VLs are preserved. */
    public boolean reloadPlugin() {
        if (!configuration.load()) {
            return false;
        }
        PluginSettings settings = configuration.settings();
        messages.load(settings.language);
        configuration.applyToChecks(checks);
        violations.enabled(configuration.raw().getBoolean("violations.enabled", true));
        players.limits(settings.historySize, settings.maxPlayerData);
        applyRuntime(settings);
        stopDiagnostics();
        startDiagnostics(settings);
        playerListener.synchronizeOnline();
        return true;
    }

    public int serverProtocol() {
        return packets.protocol().protocol;
    }

    public Component prefixed(String key) {
        return messages.component(key);
    }

    @Override
    public void onDisable() {
        ShAntiCrashAPI.uninstall();
        HandlerList.unregisterAll(this);
        profiler.stopAll(System.nanoTime());
        if (discord != null) {
            discord.close();
            discord = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (packets != null && started) {
            try {
                PacketManager.unregister(packets);
            } catch (RuntimeException e) {
                getLogger().log(Level.FINE, "Falha ao remover o listener de pacotes", e);
            }
        }
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        if (alerts != null) {
            stopDiagnostics();
        }
        if (players != null) {
            players.clear();
        }
        limiter.clear();
    }
}
