package br.com.singlehope.shanticrash.api;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckManager;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.player.PlayerDataManager;
import br.com.singlehope.shanticrash.protection.RiskSettings;
import br.com.singlehope.shanticrash.violation.CheckState;
import br.com.singlehope.shanticrash.violation.ViolationManager;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * API for other plugins, available after ShAntiCrash is enabled. All methods can be called from any
 * thread.
 */
public final class ShAntiCrashAPI {
    private static volatile ShAntiCrashAPI instance;

    private final CheckManager checks;
    private final PlayerDataManager players;
    private final ViolationManager violations;
    private final Supplier<String> mode;
    private final String version;
    private final List<Integration> integrations = new CopyOnWriteArrayList<>();

    private ShAntiCrashAPI(CheckManager checks, PlayerDataManager players, ViolationManager violations,
                           Supplier<String> mode, String version) {
        this.checks = checks;
        this.players = players;
        this.violations = violations;
        this.mode = mode;
        this.version = version;
    }

    /** @return the API, or null if the plugin is not enabled */
    public static ShAntiCrashAPI get() {
        return instance;
    }

    public static void install(CheckManager checks, PlayerDataManager players, ViolationManager violations,
                               Supplier<String> mode, String version) {
        instance = new ShAntiCrashAPI(checks, players, violations, mode, version);
    }

    public static void uninstall() {
        ShAntiCrashAPI current = instance;
        instance = null;
        if (current != null) {
            for (Integration integration : current.integrations) {
                integration.unregister();
            }
        }
    }

    public static final class ProtectionStatus {
        public final String version;
        public final String mode;
        public final int checksEnabled;
        public final int checksTotal;
        public final int connections;

        ProtectionStatus(String version, String mode, int checksEnabled, int checksTotal, int connections) {
            this.version = version;
            this.mode = mode;
            this.checksEnabled = checksEnabled;
            this.checksTotal = checksTotal;
            this.connections = connections;
        }
    }

    public static final class CheckInfo {
        public final String id;
        public final String category;
        public final String type;
        public final String description;
        public final boolean enabled;
        public final boolean behavioral;
        public final double punishAt;

        CheckInfo(Check check) {
            this.id = check.id();
            this.category = check.category();
            this.type = check.type();
            this.description = check.description();
            this.enabled = check.settings().enabled;
            this.behavioral = check.isBehavioral();
            this.punishAt = check.settings().punishAt;
        }
    }

    public static final class ViolationSummary {
        public final int totalFlags;
        public final String riskLevel;
        public final double riskScore;
        public final Map<String, Double> violations;

        ViolationSummary(int totalFlags, String riskLevel, double riskScore, Map<String, Double> violations) {
            this.totalFlags = totalFlags;
            this.riskLevel = riskLevel;
            this.riskScore = riskScore;
            this.violations = Collections.unmodifiableMap(violations);
        }
    }

    public final class Integration {
        private final String plugin;
        private final String name;
        private final List<UUID> exempted = new CopyOnWriteArrayList<>();
        private volatile boolean active = true;

        Integration(String plugin, String name) {
            this.plugin = plugin;
            this.name = name;
        }

        public String plugin() {
            return plugin;
        }

        public String name() {
            return name;
        }

        public boolean exemptBehavioral(UUID player, boolean exempt) {
            if (!active) {
                return false;
            }
            PlayerData data = players.byUuid(player);
            if (data == null) {
                return false;
            }
            data.behavioralExempt(exempt);
            if (exempt) {
                if (!exempted.contains(player)) {
                    exempted.add(player);
                }
            } else {
                exempted.remove(player);
            }
            return true;
        }

        public void unregister() {
            if (!active) {
                return;
            }
            active = false;
            for (UUID player : exempted) {
                PlayerData data = players.byUuid(player);
                if (data != null) {
                    data.behavioralExempt(false);
                }
            }
            exempted.clear();
            integrations.remove(this);
        }
    }

    public ProtectionStatus status() {
        int enabled = 0;
        for (Check check : checks.all()) {
            if (check.settings().enabled) {
                enabled++;
            }
        }
        return new ProtectionStatus(version, mode.get(), enabled, checks.all().size(), players.size());
    }

    public CheckInfo check(String id) {
        Check check = checks.byId(id);
        return check == null ? null : new CheckInfo(check);
    }

    public List<CheckInfo> checks() {
        List<CheckInfo> result = new ArrayList<>();
        for (Check check : checks.all()) {
            result.add(new CheckInfo(check));
        }
        return Collections.unmodifiableList(result);
    }

    public ViolationSummary summary(UUID player) {
        PlayerData data = players.byUuid(player);
        if (data == null) {
            return null;
        }
        long now = System.nanoTime();
        Map<String, Double> values = new LinkedHashMap<>();
        int flags = 0;
        for (Check check : checks.all()) {
            CheckState state = data.peekState(check.index());
            if (state != null && state.flags() > 0) {
                flags += state.flags();
                values.put(check.id(), state.vlAfterDecay(check.settings(), now));
            }
        }
        RiskSettings risk = violations.risk();
        return new ViolationSummary(flags, data.risk().level(risk, now).name(), data.risk().decayed(risk, now), values);
    }

    public Integration registerIntegration(Plugin plugin, String name) {
        if (plugin == null || name == null || name.isEmpty()) {
            throw new IllegalArgumentException("plugin e nome são obrigatórios");
        }
        Integration integration = new Integration(plugin.getName(), name);
        integrations.add(integration);
        return integration;
    }

    public List<String> integrations() {
        List<String> names = new ArrayList<>();
        for (Integration integration : integrations) {
            names.add(integration.plugin() + ":" + integration.name());
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Grants the higher placement limit of printer mode, for servers that know who is using a schematic
     * printer and prefer that to automatic detection.
     */
    public boolean setPrinterMode(UUID player, boolean active) {
        PlayerData data = players.byUuid(player);
        if (data == null) {
            return false;
        }
        data.printerForced(active);
        return true;
    }

    /** The current VL of each check the player has triggered, by check id. */
    public Map<String, Double> violations(UUID player) {
        Map<String, Double> result = new LinkedHashMap<>();
        PlayerData data = players.byUuid(player);
        if (data == null) {
            return result;
        }
        long now = System.nanoTime();
        for (Check check : checks.all()) {
            CheckState state = data.peekState(check.index());
            if (state != null) {
                result.put(check.id(), state.vlAfterDecay(check.settings(), now));
            }
        }
        return result;
    }

    public boolean isBedrock(UUID player) {
        PlayerData data = players.byUuid(player);
        return data != null && data.isBedrock();
    }
}
