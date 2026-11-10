package br.com.singlehope.shanticrash.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class ShAntiCrashPunishmentEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String playerName;
    private final UUID playerId;
    private final String checkId;
    private final List<String> actions;
    private final double vl;
    private final boolean cancellable;
    private boolean cancelled;

    public ShAntiCrashPunishmentEvent(boolean async, String playerName, UUID playerId, String checkId,
                                      List<String> actions, double vl, boolean cancellable) {
        super(async);
        this.playerName = playerName;
        this.playerId = playerId;
        this.checkId = checkId;
        this.actions = Collections.unmodifiableList(actions);
        this.vl = vl;
        this.cancellable = cancellable;
    }

    public String getPlayerName() {
        return playerName;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getCheckId() {
        return checkId;
    }

    public List<String> getActions() {
        return actions;
    }

    public double getVl() {
        return vl;
    }

    public boolean canBeCancelled() {
        return cancellable;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancellable && cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
