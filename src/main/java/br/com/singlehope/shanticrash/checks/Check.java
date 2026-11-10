package br.com.singlehope.shanticrash.checks;

import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.violation.ViolationManager;

/**
 * One independently configurable protection.
 *
 * <p>Subclasses run on Netty threads. They must not block, must not touch server state and must not
 * keep per player state in fields; that belongs in the player's data.
 */
public abstract class Check {
    private final String category;
    private final String type;
    private final String description;
    private final PacketKind[] kinds;
    private final String id;

    private int index = -1;
    private ViolationManager violations;
    private volatile CheckSettings settings;

    /**
     * @param kinds packets this check inspects; none means it runs for every packet
     */
    protected Check(String category, String type, String description, PacketKind... kinds) {
        this.category = category;
        this.type = type;
        this.description = description;
        this.kinds = kinds;
        this.id = category + "." + type;
        this.settings = defaults();
    }

    /**
     * Passive checks are never dispatched: other code reports through them, which gives that reporting
     * its own configuration, thresholds and alerts.
     */
    public boolean isPassive() {
        return false;
    }

    public boolean isBehavioral() {
        return false;
    }

    protected final boolean enforcing() {
        return settings.enforce;
    }

    protected final double scale(PacketContext context) {
        return settings.rateScale * context.data.riskRateFactor(context.nowNanos);
    }

    /** Built-in configuration, overridden by whatever is present in config.yml. */
    public CheckSettings defaults() {
        return new CheckSettings();
    }

    /** Reads the {@code options} block. Called for each load and reload. */
    protected void applyOptions(CheckOptions options) {
    }

    protected abstract void process(PacketContext context);

    /** Runs the check if it is active for this player. Exceptions are handled by the caller. */
    public final void run(PacketContext context) {
        CheckSettings active = settings;
        if (!active.enabled || (active.exemptBedrock && context.data.isBedrock())) {
            return;
        }
        if (context.data.isBehavioralExempt() && isBehavioral()) {
            return;
        }
        process(context);
    }

    /**
     * Reports a behaviour that can have innocent causes, so the buffer may absorb it. The packet is
     * still cancelled if the check is configured to cancel.
     */
    protected final boolean flag(PacketContext context, String info) {
        CheckSettings active = settings;
        if (!active.enabled) {
            return false;
        }
        if (active.cancel) {
            context.cancel();
        }
        return violations.flag(this, active, context.data, info, context.nowNanos, true);
    }

    /**
     * Reports a packet that can not come from a working client, skipping the buffer. It is not a
     * punishment by itself: only reaching the VL threshold is.
     */
    public final boolean reject(PacketContext context, String info) {
        CheckSettings active = settings;
        if (!active.enabled) {
            return false;
        }
        if (active.cancel) {
            context.cancel();
        }
        return violations.flag(this, active, context.data, info, context.nowNanos, false);
    }

    public final void initialize(int index, ViolationManager violations) {
        this.index = index;
        this.violations = violations;
    }

    public final void configure(CheckSettings settings) {
        applyOptions(settings.options);
        this.settings = settings;
    }

    public final CheckSettings settings() {
        return settings;
    }

    public final int index() {
        return index;
    }

    public final String category() {
        return category;
    }

    public final String type() {
        return type;
    }

    public final String id() {
        return id;
    }

    public final String description() {
        return description;
    }

    public final PacketKind[] kinds() {
        return kinds;
    }
}
