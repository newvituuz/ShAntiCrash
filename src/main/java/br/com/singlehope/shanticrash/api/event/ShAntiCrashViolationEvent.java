package br.com.singlehope.shanticrash.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class ShAntiCrashViolationEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String playerName;
    private final UUID playerId;
    private final String checkId;
    private final boolean behavioral;
    private final double vl;
    private final double punishAt;
    private final String info;

    public ShAntiCrashViolationEvent(boolean async, String playerName, UUID playerId, String checkId,
                                     boolean behavioral, double vl, double punishAt, String info) {
        super(async);
        this.playerName = playerName;
        this.playerId = playerId;
        this.checkId = checkId;
        this.behavioral = behavioral;
        this.vl = vl;
        this.punishAt = punishAt;
        this.info = info;
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

    public boolean isBehavioral() {
        return behavioral;
    }

    public double getVl() {
        return vl;
    }

    public double getPunishAt() {
        return punishAt;
    }

    public String getInfo() {
        return info;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
