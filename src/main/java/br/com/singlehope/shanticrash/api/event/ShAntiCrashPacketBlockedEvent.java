package br.com.singlehope.shanticrash.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class ShAntiCrashPacketBlockedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String playerName;
    private final UUID playerId;
    private final String packetType;
    private final String checkId;
    private final int frameBytes;

    public ShAntiCrashPacketBlockedEvent(boolean async, String playerName, UUID playerId, String packetType,
                                         String checkId, int frameBytes) {
        super(async);
        this.playerName = playerName;
        this.playerId = playerId;
        this.packetType = packetType;
        this.checkId = checkId;
        this.frameBytes = frameBytes;
    }

    public String getPlayerName() {
        return playerName;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPacketType() {
        return packetType;
    }

    public String getCheckId() {
        return checkId;
    }

    public int getFrameBytes() {
        return frameBytes;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
