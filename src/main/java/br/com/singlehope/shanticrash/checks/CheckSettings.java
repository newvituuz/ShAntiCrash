package br.com.singlehope.shanticrash.checks;

import br.com.singlehope.shanticrash.punishment.PunishmentAction;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable-by-convention configuration of one check. A reload builds new instances and swaps the
 * reference, so the Netty threads never see a half applied configuration.
 */
public final class CheckSettings {
    public boolean enabled = true;
    public boolean cancel = true;
    public boolean alert = true;
    public boolean exemptBedrock;
    public boolean enforce = true;
    public double rateScale = 1.0D;

    public double vlIncrement = 1.0D;
    public double vlMultiplier = 1.0D;
    public double vlDecayAmount = 1.0D;
    public long vlDecayIntervalNanos = 300_000_000_000L;
    public double vlMin;
    public double vlMax = 100.0D;
    public double punishAt = 5.0D;
    public double alertMinVl = 1.0D;
    public boolean resetOnPunish = true;

    public boolean bufferEnabled;
    public double bufferMax = 3.0D;
    public double bufferMultiply = 0.5D;
    public double bufferDecayPerSecond = 0.5D;

    public Set<PunishmentAction> actions = EnumSet.of(PunishmentAction.KICK);
    public List<String> commands = Collections.emptyList();
    public String kickMessage = "";

    public CheckOptions options = CheckOptions.EMPTY;

    /** Fluent helpers so each check can state its built-in defaults in one line. */
    public CheckSettings punishAt(double value) {
        this.punishAt = value;
        return this;
    }

    public CheckSettings cancel(boolean value) {
        this.cancel = value;
        return this;
    }

    public CheckSettings actions(PunishmentAction first, PunishmentAction... rest) {
        this.actions = EnumSet.of(first, rest);
        return this;
    }

    public CheckSettings buffer(double max, double multiply, double decayPerSecond) {
        this.bufferEnabled = true;
        this.bufferMax = max;
        this.bufferMultiply = multiply;
        this.bufferDecayPerSecond = decayPerSecond;
        return this;
    }

    public CheckSettings exemptBedrock(boolean value) {
        this.exemptBedrock = value;
        return this;
    }
}
