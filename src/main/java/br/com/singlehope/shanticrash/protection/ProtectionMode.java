package br.com.singlehope.shanticrash.protection;

import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;

public enum ProtectionMode {
    STRICT(0.75D, 0.75D, true),
    BALANCED(1.0D, 1.0D, true),
    COMPATIBILITY(1.5D, 1.5D, true),
    MONITOR_ONLY(1.0D, 1.0D, false);

    public final double rateScale;
    public final double punishScale;
    public final boolean enforceBehavioral;

    ProtectionMode(double rateScale, double punishScale, boolean enforceBehavioral) {
        this.rateScale = rateScale;
        this.punishScale = punishScale;
        this.enforceBehavioral = enforceBehavioral;
    }

    public CheckSettings apply(boolean behavioral, CheckSettings settings) {
        if (!behavioral) {
            return settings;
        }
        settings.rateScale = rateScale;
        settings.punishAt = Math.max(1.0D, settings.punishAt * punishScale);
        if (!enforceBehavioral) {
            settings.enforce = false;
            settings.cancel = false;
            settings.actions = EnumSet.of(PunishmentAction.ALERT);
            settings.commands = Collections.emptyList();
        }
        return settings;
    }

    public static ProtectionMode parse(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
