package br.com.singlehope.shanticrash.configuration;

import br.com.singlehope.shanticrash.alerts.DiscordNotifier;
import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.metrics.Profiler;
import br.com.singlehope.shanticrash.packet.decoder.OutgoingItemSanitizer;
import br.com.singlehope.shanticrash.protection.ProtectionMode;
import br.com.singlehope.shanticrash.protection.RiskSettings;
import br.com.singlehope.shanticrash.checks.CheckManager;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.checks.item.DecodingGuard;
import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.logging.EventLogger;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Loads config.yml and hands validated values to the rest of the plugin.
 *
 * <p>The file from the jar acts as the defaults, so a value missing in an older file falls back without
 * rewriting the user's file and losing its comments. An invalid value is reported and replaced, it
 * never prevents the plugin from protecting the server.
 */
public final class ConfigurationManager {
    private final JavaPlugin plugin;
    private YamlConfiguration config;
    private volatile PluginSettings settings = new PluginSettings();

    public ConfigurationManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public PluginSettings settings() {
        return settings;
    }

    public YamlConfiguration raw() {
        return config;
    }

    /** Reads the file, keeping the previous configuration if it can not be parsed. */
    public boolean load() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            plugin.saveResource("config.yml", false);
        }
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.loadFromString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "config.yml inválido, mantendo a configuração anterior: " + e.getMessage());
            preserveBroken(file);
            if (config != null) {
                return false;
            }
            loaded = new YamlConfiguration();
        }
        YamlConfiguration defaults = loadResource("config.yml");
        if (defaults != null) {
            loaded.setDefaults(defaults);
        }
        this.config = loaded;
        this.settings = readSettings(loaded);
        return true;
    }

    private void preserveBroken(File file) {
        try {
            Path copy = file.toPath().resolveSibling("config.yml.invalido");
            Files.copy(file.toPath(), copy, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // The original is untouched, the copy is only a convenience.
        }
    }

    public YamlConfiguration loadResource(String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Recurso ilegível: " + name, e);
            return null;
        }
    }

    private PluginSettings readSettings(YamlConfiguration c) {
        PluginSettings s = new PluginSettings();
        s.language = c.getString("general.language", s.language);
        s.debug = c.getBoolean("general.debug", false);
        s.alertsOnJoin = c.getBoolean("general.alerts-on-join", true);
        s.hiddenCommand = c.getBoolean("general.hidden-command", false);
        s.bypassEnabled = c.getBoolean("general.bypass-enabled", false);

        s.maxPlayerData = positive(c, "performance.max-player-data", s.maxPlayerData);
        s.cleanupIntervalSeconds = positive(c, "performance.cleanup-interval", (int) s.cleanupIntervalSeconds);
        s.packetMonitoring = c.getBoolean("performance.packet-monitoring", false);
        s.heavyPacketNanos = c.getLong("performance.heavy-packet-threshold", s.heavyPacketNanos);
        s.watchdogEnabled = c.getBoolean("performance.netty-watchdog.enabled", false);
        s.watchdogMaxMillis = positive(c, "performance.netty-watchdog.max-time", (int) s.watchdogMaxMillis);
        s.watchdogCheckMillis = positive(c, "performance.netty-watchdog.check-frequency", (int) s.watchdogCheckMillis);
        s.watchdogCooldownMillis = positive(c, "performance.netty-watchdog.report-cooldown", (int) s.watchdogCooldownMillis);
        s.historySize = positive(c, "violations.history-size", s.historySize);

        s.punishmentsEnabled = c.getBoolean("punishments.enabled", true);
        s.defaultAction = action(c.getString("punishments.default-action", "KICK"), PunishmentAction.KICK);
        s.punishmentCooldownNanos = c.getLong("punishments.cooldown", 1000L) * 1_000_000L;
        s.defaultKickMessage = c.getString("punishments.kick-message", "");
        s.maxQueuedCommands = positive(c, "punishments.max-queued-commands", s.maxQueuedCommands);

        s.alertsEnabled = c.getBoolean("alerts.enabled", true);
        s.alertsStore = c.getBoolean("alerts.store", true);
        s.alertsConsole = c.getBoolean("alerts.console", false);
        s.alertCooldownNanos = c.getLong("alerts.cooldown", 1000L) * 1_000_000L;
        s.maxAlertsPerSecond = positive(c, "alerts.max-per-second", s.maxAlertsPerSecond);
        s.disabledAlertCategories = new HashSet<>(c.getStringList("alerts.disabled-categories"));

        s.loggingEnabled = c.getBoolean("logging.enabled", true);
        s.logCancelledPackets = c.getBoolean("logging.cancelled-packets", true);
        s.logOutput = output(c.getString("logging.output", "FILE"));
        s.logMaxFileBytes = positive(c, "logging.max-file-size-mb", 10) * 1024L * 1024L;
        s.logRetentionDays = c.getInt("logging.retention-days", c.getInt("alerts.retention-days", 14));
        s.logQueueCapacity = positive(c, "logging.queue-capacity", s.logQueueCapacity);

        s.geyserSupport = c.getBoolean("integrations.geyser.enabled", true);
        s.bedrockFloodMultiplier = c.getDouble("integrations.geyser.flood-multiplier", 2.0D);
        s.floodgateSupport = c.getBoolean("integrations.floodgate.enabled", true);
        s.viaSupport = c.getBoolean("integrations.viaversion.enabled", true);
        s.viaOldClientFloodMultiplier = c.getDouble("integrations.viaversion.old-client-flood-multiplier", 1.5D);
        s.viaOldClientProtocol = c.getInt("integrations.viaversion.old-client-below-protocol", 107);

        String modeName = c.getString("protection.mode", "BALANCED");
        ProtectionMode mode = ProtectionMode.parse(modeName);
        if (mode == null) {
            plugin.getLogger().warning("protection.mode desconhecido: " + modeName + ", usando BALANCED");
            mode = ProtectionMode.BALANCED;
        }
        s.mode = mode;
        s.risk = readRisk(c);

        s.maxConnectionsPerAddress = Math.max(0, c.getInt("connection.max-per-address", 10));
        s.exemptLoopback = c.getBoolean("connection.exempt-loopback", true);
        s.trustedProxies = new ArrayList<>(c.getStringList("connection.trusted-proxies"));

        s.profiler = new Profiler.Settings(
                Math.max(1, c.getInt("profiler.duration", 60)) * 1_000_000_000L,
                Math.max(1, c.getInt("profiler.sample-every", 1)),
                Math.max(1, c.getInt("profiler.max-sessions", 5)));

        s.discordEnabled = c.getBoolean("discord.enabled", false);
        s.discordUrl = c.getString("discord.webhook-url", "");
        if (s.discordEnabled && !DiscordNotifier.validUrl(s.discordUrl)) {
            plugin.getLogger().warning("discord.webhook-url não é um webhook do Discord válido; alertas no Discord desativados.");
            s.discordEnabled = false;
        }
        s.discordTimeoutMillis = c.getInt("discord.timeout-ms", 3000);
        s.discordMaxPerMinute = c.getInt("discord.max-per-minute", 10);
        s.discordGroupMillis = c.getLong("discord.group-seconds", 10L) * 1000L;
        s.discordQueueCapacity = c.getInt("discord.queue-capacity", 100);
        s.discordUsername = c.getString("discord.username", "ShAntiCrash");
        s.discordIncludeFlags = c.getBoolean("discord.include-flags", false);

        s.sanitizer = new OutgoingItemSanitizer.Settings(
                c.getBoolean("outgoing-sanitizer.enabled", false),
                c.getInt("outgoing-sanitizer.max-lore-lines", 256),
                c.getInt("outgoing-sanitizer.max-name-chars", 2048),
                c.getInt("outgoing-sanitizer.max-text-nodes", 512),
                c.getInt("outgoing-sanitizer.max-nested-depth", 4));

        s.netherPortalDelayMillis = c.getLong("mechanics.nether-portal-delay", -1L);
        s.maxArrowVelocity = c.getDouble("mechanics.max-arrow-velocity", -1.0D);
        s.shearsCooldownMillis = c.getLong("mechanics.shears-cooldown", -1L);
        s.breakClosesInventory = c.getBoolean("mechanics.break-close-inventory", false);
        s.redstoneThrottleEnabled = c.getBoolean("mechanics.redstone-throttle.enabled", false);
        s.redstoneThrottleMaxPerSecond = positive(c, "mechanics.redstone-throttle.max-per-chunk-per-second", s.redstoneThrottleMaxPerSecond);
        s.containerOpenDelayMillis = c.getLong("mechanics.interact-container-delay", -1L);
        return s;
    }

    private RiskSettings readRisk(YamlConfiguration c) {
        RiskSettings d = RiskSettings.DEFAULT;
        try {
            return new RiskSettings(
                    c.getBoolean("risk.enabled", d.enabled),
                    c.getLong("risk.half-life", d.halfLifeNanos / 1_000_000_000L) * 1_000_000_000L,
                    c.getDouble("risk.thresholds.moderate", d.moderate),
                    c.getDouble("risk.thresholds.high", d.high),
                    c.getDouble("risk.thresholds.critical", d.critical),
                    c.getDouble("risk.weights.structural", d.structuralWeight),
                    c.getDouble("risk.weights.behavioral", d.behavioralWeight),
                    c.getDouble("risk.moderate-rate-factor", d.moderateRateFactor),
                    c.getInt("risk.critical-evidence", d.criticalEvidence),
                    c.getLong("risk.evidence-window", d.evidenceWindowNanos / 1_000_000_000L) * 1_000_000_000L);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Seção risk inválida, usando os valores padrão: " + e.getMessage());
            return d;
        }
    }

    private int positive(YamlConfiguration c, String path, int fallback) {
        int value = c.getInt(path, fallback);
        if (value <= 0) {
            plugin.getLogger().warning(path + " precisa ser maior que zero, usando " + fallback);
            return fallback;
        }
        return value;
    }

    private PunishmentAction action(String name, PunishmentAction fallback) {
        try {
            return PunishmentAction.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Ação de punição desconhecida: " + name);
            return fallback;
        }
    }

    private EventLogger.Output output(String name) {
        try {
            return EventLogger.Output.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("logging.output desconhecido: " + name);
            return EventLogger.Output.FILE;
        }
    }

    /** Applies the file to every check, and then the settings derived from their state. */
    public void applyToChecks(CheckManager checks) {
        List<Check> all = checks.all();
        CheckSettings[] prepared = new CheckSettings[all.size()];
        for (int i = 0; i < prepared.length; i++) {
            Check check = all.get(i);
            try {
                prepared[i] = applyMode(check, readCheck(check));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Configuração inválida em " + check.id()
                        + ", usando os valores padrão: " + e);
                prepared[i] = applyMode(check, check.defaults());
            }
        }
        ItemLimits limits = readItemLimits();
        for (int i = 0; i < prepared.length; i++) {
            Check check = all.get(i);
            try {
                check.configure(prepared[i]);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Opções inválidas em " + check.id()
                        + ", usando os valores padrão: " + e);
                check.configure(applyMode(check, check.defaults()));
            }
        }
        checks.applyItemLimits(limits);
        applyDecodingGuard(checks.decodingGuard);
    }

    private CheckSettings applyMode(Check check, CheckSettings result) {
        ProtectionMode mode = settings.mode;
        return mode == null ? result : mode.apply(check.isBehavioral(), result);
    }

    private CheckSettings readCheck(Check check) {
        CheckSettings result = check.defaults();
        boolean categoryEnabled = config.getBoolean("checks." + check.category() + ".enabled", true);
        ConfigurationSection section = config.getConfigurationSection("checks." + check.category() + "." + check.type());
        result.enabled = categoryEnabled;
        result.kickMessage = settings.defaultKickMessage;
        if (result.actions.equals(EnumSet.of(PunishmentAction.KICK))) {
            result.actions = EnumSet.of(settings.defaultAction);
        }
        if (section == null) {
            return result;
        }
        result.enabled = categoryEnabled && section.getBoolean("enabled", true);
        result.cancel = section.getBoolean("cancel", result.cancel);
        result.alert = section.getBoolean("alert", result.alert);
        result.exemptBedrock = section.getBoolean("exempt-bedrock", result.exemptBedrock);

        ConfigurationSection vl = section.getConfigurationSection("vl");
        if (vl != null) {
            result.vlIncrement = vl.getDouble("increment", result.vlIncrement);
            result.vlMultiplier = vl.getDouble("multiplier", result.vlMultiplier);
            result.vlDecayAmount = vl.getDouble("decay-amount", result.vlDecayAmount);
            if (vl.contains("decay-interval")) {
                result.vlDecayIntervalNanos = vl.getLong("decay-interval") * 1_000_000_000L;
            }
            result.vlMin = vl.getDouble("min", result.vlMin);
            result.vlMax = vl.getDouble("max", result.vlMax);
            result.punishAt = vl.getDouble("punish-at", result.punishAt);
            result.alertMinVl = vl.getDouble("alert-min", result.alertMinVl);
            result.resetOnPunish = vl.getBoolean("reset-on-punish", result.resetOnPunish);
        }
        ConfigurationSection buffer = section.getConfigurationSection("buffer");
        if (buffer != null) {
            result.bufferEnabled = buffer.getBoolean("enabled", true);
            result.bufferMax = buffer.getDouble("max", result.bufferMax);
            result.bufferMultiply = buffer.getDouble("multiplier", result.bufferMultiply);
            result.bufferDecayPerSecond = buffer.getDouble("decay", result.bufferDecayPerSecond);
        }
        ConfigurationSection punishment = section.getConfigurationSection("punishment");
        if (punishment != null) {
            if (punishment.isList("actions")) {
                Set<PunishmentAction> actions = EnumSet.noneOf(PunishmentAction.class);
                for (String name : punishment.getStringList("actions")) {
                    actions.add(action(name, PunishmentAction.NONE));
                }
                result.actions = actions;
            }
            if (punishment.isList("commands")) {
                result.commands = new ArrayList<>(punishment.getStringList("commands"));
            }
            result.kickMessage = punishment.getString("message", result.kickMessage);
        }
        ConfigurationSection options = section.getConfigurationSection("options");
        if (options != null) {
            Map<String, Object> flat = options.getValues(true);
            result.options = new CheckOptions(flat);
        }
        return result;
    }

    private ItemLimits readItemLimits() {
        ItemLimits limits = new ItemLimits();
        String p = "item-protection.";
        limits.maxItemDepth = positive(config, p + "max-item-depth", limits.maxItemDepth);
        limits.maxItemsPerPacket = positive(config, p + "max-items-per-packet", limits.maxItemsPerPacket);
        limits.maxNbtDepth = positive(config, p + "max-nbt-depth", limits.maxNbtDepth);
        limits.maxNbtNodes = positive(config, p + "max-nbt-nodes", limits.maxNbtNodes);
        limits.maxStringBytes = positive(config, p + "max-string-length", limits.maxStringBytes);
        limits.maxArrayLength = positive(config, p + "max-array-length", limits.maxArrayLength);
        limits.maxListLength = positive(config, p + "max-list-length", limits.maxListLength);
        limits.maxContainerItems = positive(config, p + "max-container-items", limits.maxContainerItems);
        limits.maxChargedProjectiles = positive(config, p + "max-charged-projectiles", limits.maxChargedProjectiles);
        limits.maxProfileProperties = positive(config, p + "max-profile-properties", limits.maxProfileProperties);
        limits.maxProfileBytes = positive(config, p + "max-profile-bytes", limits.maxProfileBytes);
        limits.maxToolRules = positive(config, p + "max-tool-rules", limits.maxToolRules);
        limits.maxBundleItems = positive(config, p + "max-bundle-items", limits.maxBundleItems);
        limits.maxLoreLines = positive(config, p + "max-lore-lines", limits.maxLoreLines);
        limits.maxPredicates = positive(config, p + "max-predicates", limits.maxPredicates);
        limits.maxTextNodesTotal = positive(config, p + "text.max-total-nodes", limits.maxTextNodesTotal);
        limits.maxComponents = positive(config, p + "max-component-count", limits.maxComponents);
        limits.maxItemBytes = positive(config, p + "max-item-bytes", limits.maxItemBytes);
        limits.maxChangedSlots = positive(config, p + "max-changed-slots", limits.maxChangedSlots);
        limits.maxAmount = positive(config, p + "max-amount", limits.maxAmount);
        limits.maxBookPages = positive(config, p + "max-book-pages", limits.maxBookPages);
        limits.maxBookPageBytes = positive(config, p + "max-book-page-bytes", limits.maxBookPageBytes);
        limits.maxTranslatePerString = positive(config, p + "text.max-translate", limits.maxTranslatePerString);
        limits.maxExtraPerString = positive(config, p + "text.max-extra", limits.maxExtraPerString);
        limits.maxJsonDepth = positive(config, p + "text.max-depth", limits.maxJsonDepth);
        limits.maxTextNodes = positive(config, p + "text.max-nodes", limits.maxTextNodes);
        limits.maxProcessingNanos = config.getLong(p + "max-processing-nanos", limits.maxProcessingNanos);
        return limits;
    }

    private void applyDecodingGuard(DecodingGuard guard) {
        String name = config.getString("item-protection.disable-item-decoding", "SMART");
        DecodingGuard.Mode mode;
        try {
            mode = DecodingGuard.Mode.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("disable-item-decoding desconhecido: " + name + ", usando SMART");
            mode = DecodingGuard.Mode.SMART;
        }
        guard.configure(mode,
                new HashSet<>(listOr("item-protection.creative-exempt-components")),
                new HashSet<>(listOr("item-protection.creative-exempt-nbt")));
    }

    private List<String> listOr(String path) {
        List<String> values = config.getStringList(path);
        return values == null ? Collections.<String>emptyList() : values;
    }
}
