package br.com.singlehope.shanticrash.configuration;

import br.com.singlehope.shanticrash.logging.EventLogger;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;

import br.com.singlehope.shanticrash.metrics.Profiler;
import br.com.singlehope.shanticrash.packet.decoder.OutgoingItemSanitizer;
import br.com.singlehope.shanticrash.protection.ProtectionMode;
import br.com.singlehope.shanticrash.protection.RiskSettings;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Snapshot of the settings that do not belong to a single check. Replaced as a whole on reload. */
public final class PluginSettings {
    public String language = "pt-BR";
    public boolean debug;
    public boolean alertsOnJoin = true;
    public boolean hiddenCommand;
    public boolean bypassEnabled;

    public int maxPlayerData = 5000;
    public long cleanupIntervalSeconds = 300L;
    public boolean packetMonitoring;
    public long heavyPacketNanos = 5_000_000L;
    public boolean watchdogEnabled;
    public long watchdogMaxMillis = 50L;
    public long watchdogCheckMillis = 25L;
    public long watchdogCooldownMillis = 5_000L;
    public int historySize = 32;

    public boolean punishmentsEnabled = true;
    public PunishmentAction defaultAction = PunishmentAction.KICK;
    public long punishmentCooldownNanos = 1_000_000_000L;
    public String defaultKickMessage = "";
    public int maxQueuedCommands = 256;

    public boolean alertsEnabled = true;
    public boolean alertsStore = true;
    public boolean alertsConsole;
    public long alertCooldownNanos = 1_000_000_000L;
    public int maxAlertsPerSecond = 20;
    public Set<String> disabledAlertCategories = Collections.emptySet();

    public boolean loggingEnabled = true;
    public boolean logCancelledPackets = true;
    public EventLogger.Output logOutput = EventLogger.Output.FILE;
    public long logMaxFileBytes = 10L * 1024L * 1024L;
    public int logRetentionDays = 14;
    public int logQueueCapacity = 8192;

    public boolean geyserSupport = true;
    public double bedrockFloodMultiplier = 2.0D;
    public boolean floodgateSupport = true;
    public boolean viaSupport = true;
    public double viaOldClientFloodMultiplier = 1.5D;
    public int viaOldClientProtocol = 107;

    public ProtectionMode mode = ProtectionMode.BALANCED;
    public RiskSettings risk = RiskSettings.DEFAULT;

    public int maxConnectionsPerAddress = 10;
    public boolean exemptLoopback = true;
    public List<String> trustedProxies = Collections.emptyList();

    public Profiler.Settings profiler = Profiler.Settings.DEFAULT;

    public boolean discordEnabled;
    public String discordUrl = "";
    public int discordTimeoutMillis = 3000;
    public int discordMaxPerMinute = 10;
    public long discordGroupMillis = 10_000L;
    public int discordQueueCapacity = 100;
    public String discordUsername = "ShAntiCrash";
    public boolean discordIncludeFlags;

    public OutgoingItemSanitizer.Settings sanitizer = OutgoingItemSanitizer.Settings.DISABLED;

    public long netherPortalDelayMillis = -1L;
    public double maxArrowVelocity = -1.0D;
    public long shearsCooldownMillis = -1L;
    public boolean breakClosesInventory;
    public boolean redstoneThrottleEnabled;
    public int redstoneThrottleMaxPerSecond = 512;
    public long containerOpenDelayMillis = -1L;
}
