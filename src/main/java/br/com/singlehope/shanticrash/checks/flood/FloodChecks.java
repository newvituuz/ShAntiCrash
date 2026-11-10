package br.com.singlehope.shanticrash.checks.flood;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.flood.FloodLimit;
import br.com.singlehope.shanticrash.flood.FloodState;
import br.com.singlehope.shanticrash.flood.WindowRuleSet;
import br.com.singlehope.shanticrash.flood.WindowRuleState;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import br.com.singlehope.shanticrash.packet.FloodGroup;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.player.PlayerData;

import java.util.EnumMap;
import java.util.Map;

/** Token bucket rate limiting per connection, with a different limit for each kind of packet. */
public final class FloodChecks {
    /**
     * Default limits. A vanilla client runs at 20 ticks per second and sends at most a few packets of
     * one kind per tick; the burst sizes leave room for the queue a client flushes after a lag spike.
     * {@code drop} is true only when losing the packet is harmless; dropping movement would rubberband
     * the player, so those limits only count violations.
     */
    private static final Map<FloodGroup, FloodLimit> DEFAULTS = new EnumMap<>(FloodGroup.class);

    static {
        put(FloodGroup.MOVEMENT, 100, 400, false);
        put(FloodGroup.VEHICLE, 100, 400, false);
        put(FloodGroup.CLICK_WINDOW, 60, 200, true);
        put(FloodGroup.CLICK_WINDOW_BUTTON, 15, 45, true);
        put(FloodGroup.CLOSE_WINDOW, 15, 45, true);
        put(FloodGroup.CREATIVE_SLOT, 50, 150, true);
        put(FloodGroup.BLOCK_PLACE, 40, 120, false);
        put(FloodGroup.USE_ITEM, 40, 120, false);
        put(FloodGroup.DIGGING, 60, 200, false);
        put(FloodGroup.INTERACT_ENTITY, 40, 120, false);
        put(FloodGroup.ANIMATION, 40, 120, false);
        put(FloodGroup.HELD_ITEM_CHANGE, 60, 180, false);
        put(FloodGroup.PLUGIN_MESSAGE, 60, 400, true);
        put(FloodGroup.EDIT_BOOK, 2, 8, true);
        put(FloodGroup.UPDATE_SIGN, 5, 15, true);
        put(FloodGroup.NAME_ITEM, 20, 60, true);
        put(FloodGroup.TAB_COMPLETE, 20, 60, true);
        put(FloodGroup.CHAT, 10, 30, true);
        put(FloodGroup.CRAFT_RECIPE, 15, 45, true);
        put(FloodGroup.RECIPE_BOOK, 15, 45, true);
        put(FloodGroup.KEEP_ALIVE, 120, 400, false);
        put(FloodGroup.CLIENT_TICK, 100, 400, false);
        put(FloodGroup.ENTITY_ACTION, 40, 120, false);
        put(FloodGroup.COMMAND_BLOCK, 5, 15, true);
        put(FloodGroup.OTHER, 100, 400, false);
    }

    private FloodChecks() {
    }

    private static void put(FloodGroup group, double rate, double burst, boolean drop) {
        DEFAULTS.put(group, new FloodLimit(rate, burst, 1_000L, 5_000L, 20, drop));
    }

    public static FloodLimit defaultLimit(FloodGroup group) {
        return DEFAULTS.get(group);
    }

    /** Limit per packet group. */
    public static final class PacketLimits extends Check {
        private volatile FloodLimit[] limits = new FloodLimit[FloodGroup.values().length];
        private volatile double printerMultiplier = 3.0D;
        private volatile WindowRuleSet[] windowRules = emptyWindows();

        private static WindowRuleSet[] emptyWindows() {
            WindowRuleSet[] result = new WindowRuleSet[FloodGroup.values().length];
            for (FloodGroup group : FloodGroup.values()) {
                result[group.ordinal()] = WindowRuleSet.EMPTY;
            }
            return result;
        }

        private static int number(Map<String, Object> map, String key, int fallback) {
            Object raw = map.get(key);
            return raw instanceof Number ? ((Number) raw).intValue() : fallback;
        }

        private static WindowRuleSet parseRules(List<Map<String, Object>> source) {
            List<WindowRuleSet.Rule> valid = new ArrayList<>();
            for (Map<String, Object> entry : source) {
                if (valid.size() == 4) {
                    break;
                }
                int interval = number(entry, "interval-ms", 200);
                int maximum = number(entry, "max", 36);
                int minBytes = number(entry, "min-bytes", 0);
                int maxBytes = number(entry, "max-bytes", Integer.MAX_VALUE);
                String actionName = String.valueOf(entry.containsKey("action") ? entry.get("action") : "FLAG");
                try {
                    WindowRuleSet.Action action = WindowRuleSet.Action.valueOf(actionName.toUpperCase(Locale.ROOT));
                    valid.add(new WindowRuleSet.Rule(interval, maximum, minBytes, maxBytes, action));
                } catch (IllegalArgumentException ignored) {
                    // An invalid rule is safely skipped. No exceptions are thrown on a Netty thread.
                }
            }
            return new WindowRuleSet(valid.toArray(new WindowRuleSet.Rule[0]));
        }

        @Override
        public boolean isBehavioral() {
            return true;
        }

        public PacketLimits() {
            super("flood", "packet-limits", "Frequência de um tipo de pacote acima do limite.");
            for (Map.Entry<FloodGroup, FloodLimit> entry : DEFAULTS.entrySet()) {
                limits[entry.getKey().ordinal()] = entry.getValue();
            }
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().cancel(false).punishAt(6.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            FloodLimit[] loaded = new FloodLimit[FloodGroup.values().length];
            for (FloodGroup group : FloodGroup.values()) {
                FloodLimit fallback = DEFAULTS.get(group);
                if (fallback == null) {
                    continue;
                }
                String prefix = "limits." + group.name() + ".";
                loaded[group.ordinal()] = new FloodLimit(
                        options.getDouble(prefix + "rate", fallback.rate),
                        options.getDouble(prefix + "burst", fallback.burst),
                        options.getLong(prefix + "observation-ms", fallback.observationNanos / 1_000_000L),
                        options.getLong(prefix + "recovery-ms", fallback.recoveryNanos / 1_000_000L),
                        options.getInt(prefix + "max-overflows", fallback.maxOverflows),
                        options.getBoolean(prefix + "drop", fallback.drop));
            }
            limits = loaded;
            printerMultiplier = options.getDouble("printer-multiplier", 3.0D);
            WindowRuleSet[] configured = emptyWindows();
            if (options.getBoolean("advanced-rules-enabled", false)) {
                for (FloodGroup group : FloodGroup.values()) {
                    configured[group.ordinal()] = parseRules(options.getMapList("advanced-rules." + group.name()));
                }
            }
            windowRules = configured;
        }

        @Override
        protected void process(PacketContext context) {
            FloodGroup group = context.kind.group();
            FloodLimit limit = limits[group.ordinal()];
            if (limit == null) {
                return;
            }
            PlayerData data = context.data;
            double multiplier = data.floodMultiplier() * scale(context);
            if (group == FloodGroup.BLOCK_PLACE && data.isPrinterMode()) {
                multiplier *= printerMultiplier;
            }
            FloodState.Result result = data.floodState(group).account(limit, multiplier, 1.0D, context.nowNanos);
            if (result != FloodState.Result.ALLOWED) {
                if (limit.drop && enforcing()) {
                    context.cancel();
                }
                if (result == FloodState.Result.VIOLATION) {
                    flag(context, group + " acima de " + (long) (limit.rate * multiplier) + "/s");
                }
                return;
            }
            WindowRuleSet activeWindows = windowRules[group.ordinal()];
            if (activeWindows.rules.length == 0) {
                return;
            }
            WindowRuleState state = data.windowRuleState(group);
            WindowRuleSet.Action action = state.account(activeWindows, context.frameSize, context.nowNanos);
            if (action == WindowRuleSet.Action.DROP && enforcing()) {
                context.cancel();
            }
            if ((action == WindowRuleSet.Action.DROP || action == WindowRuleSet.Action.FLAG)
                    && state.shouldFlag(context.nowNanos)) {
                flag(context, group + " janela curta excedida (" + context.frameSize + " bytes)");
            }
        }
    }

    /** Ceiling for the sum of everything, which catches floods spread across many packet types. */
    public static final class Global extends Check {
        private volatile FloodLimit limit = new FloodLimit(800D, 2500D, 2_000L, 10_000L, 50, true);

        @Override
        public boolean isBehavioral() {
            return true;
        }

        public Global() {
            super("flood", "global", "Volume total de pacotes acima do limite.");
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().cancel(false).punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            limit = new FloodLimit(
                    options.getDouble("packets-per-second", 800D),
                    options.getDouble("burst", 2500D),
                    options.getLong("observation-ms", 2_000L),
                    options.getLong("recovery-ms", 10_000L),
                    options.getInt("max-overflows", 50),
                    options.getBoolean("drop", true));
        }

        @Override
        protected void process(PacketContext context) {
            FloodLimit active = limit;
            FloodState.Result result = context.data.globalFlood.account(active,
                    context.data.floodMultiplier() * scale(context), 1.0D, context.nowNanos);
            if (result == FloodState.Result.ALLOWED) {
                return;
            }
            if (active.drop && enforcing()) {
                context.cancel();
            }
            if (result == FloodState.Result.VIOLATION) {
                flag(context, "acima de " + (long) active.rate + " pacotes/s");
            }
        }
    }

    /** Large frames are expensive even in small numbers, so they get a much lower limit of their own. */
    public static final class BigFrames extends Check {
        private volatile FloodLimit limit = new FloodLimit(8D, 40D, 2_000L, 10_000L, 8, true);
        private volatile int threshold = 4096;

        @Override
        public boolean isBehavioral() {
            return true;
        }

        public BigFrames() {
            super("flood", "big-frames", "Muitos pacotes grandes em pouco tempo.");
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().cancel(false).punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            threshold = options.getInt("threshold-bytes", 4096);
            limit = new FloodLimit(
                    options.getDouble("packets-per-second", 8D),
                    options.getDouble("burst", 40D),
                    options.getLong("observation-ms", 2_000L),
                    options.getLong("recovery-ms", 10_000L),
                    options.getInt("max-overflows", 8),
                    options.getBoolean("drop", true));
        }

        @Override
        protected void process(PacketContext context) {
            if (context.frameSize < threshold) {
                return;
            }
            FloodLimit active = limit;
            FloodState.Result result = context.data.bigFrameFlood.account(active,
                    context.data.floodMultiplier() * scale(context), 1.0D, context.nowNanos);
            if (result == FloodState.Result.ALLOWED) {
                return;
            }
            if (active.drop && enforcing()) {
                context.cancel();
            }
            if (result == FloodState.Result.VIOLATION) {
                flag(context, "pacotes >= " + threshold + " bytes acima de " + (long) active.rate + "/s");
            }
        }
    }
}
