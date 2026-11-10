package br.com.singlehope.shanticrash.api.event;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EventDispatcher {
    private final Logger logger;
    private volatile long lastErrorNanos;

    public EventDispatcher(Logger logger) {
        this.logger = logger;
    }

    static boolean listening(HandlerList handlers) {
        return handlers.getRegisteredListeners().length > 0;
    }

    private static boolean async() {
        try {
            return !Bukkit.isPrimaryThread();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void call(Event event) {
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException e) {
            long now = System.nanoTime();
            if (now - lastErrorNanos > 60_000_000_000L) {
                lastErrorNanos = now;
                logger.log(Level.WARNING, "Um listener de evento do ShAntiCrash falhou", e);
            }
        }
    }

    public void violation(Check check, PlayerData data, double vl, String info) {
        if (!listening(ShAntiCrashViolationEvent.getHandlerList())) {
            return;
        }
        call(new ShAntiCrashViolationEvent(async(), data.handle().name(), data.handle().uuid(), check.id(),
                check.isBehavioral(), vl, check.settings().punishAt, info));
    }

    public void blocked(PlayerData data, String packetType, String checkId, int frameBytes) {
        if (!listening(ShAntiCrashPacketBlockedEvent.getHandlerList())) {
            return;
        }
        call(new ShAntiCrashPacketBlockedEvent(async(), data.handle().name(), data.handle().uuid(), packetType,
                checkId, frameBytes));
    }

    public boolean punishment(Check check, PlayerData data, Set<PunishmentAction> actions, double vl) {
        if (!listening(ShAntiCrashPunishmentEvent.getHandlerList())) {
            return true;
        }
        List<String> names = new ArrayList<>(actions.size());
        for (PunishmentAction action : actions) {
            names.add(action.name());
        }
        ShAntiCrashPunishmentEvent event = new ShAntiCrashPunishmentEvent(async(), data.handle().name(),
                data.handle().uuid(), check.id(), names, vl, check.isBehavioral());
        call(event);
        return !event.isCancelled();
    }
}
