package br.com.singlehope.shanticrash.player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Owner of the per connection state. Maps are bounded by {@code max-player-data}, removal is tied to
 * the channel closing, and a periodic sweep catches anything that missed that event, so a connection
 * flood can not grow them forever.
 */
public final class PlayerDataManager {
    private final Map<Object, PlayerData> byConnection = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerData> byUuid = new ConcurrentHashMap<>();
    private final int checkCount;
    private volatile int historySize;
    private volatile int maxPlayers;
    private volatile Consumer<PlayerData> removalListener = data -> { };

    public PlayerDataManager(int checkCount, int historySize, int maxPlayers) {
        this.checkCount = checkCount;
        this.historySize = historySize;
        this.maxPlayers = maxPlayers;
    }

    public void limits(int historySize, int maxPlayers) {
        this.historySize = historySize;
        this.maxPlayers = maxPlayers;
    }

    /** Creates the state of a connection, or returns null if the limit has been reached. */
    public PlayerData create(Object connection, PlayerHandle handle) {
        PlayerData existing = byConnection.get(connection);
        if (existing != null) {
            return existing;
        }
        if (byConnection.size() >= maxPlayers) {
            return null;
        }
        PlayerData created = new PlayerData(handle, checkCount, historySize);
        PlayerData raced = byConnection.putIfAbsent(connection, created);
        return raced != null ? raced : created;
    }

    public PlayerData get(Object connection) {
        return byConnection.get(connection);
    }

    public void bindUuid(UUID uuid, PlayerData data) {
        byUuid.put(uuid, data);
    }

    public PlayerData byUuid(UUID uuid) {
        return uuid == null ? null : byUuid.get(uuid);
    }

    /** Linear on purpose: it serves commands only, and no second map has to be kept in sync. */
    public PlayerData byName(String name) {
        for (PlayerData data : byUuid.values()) {
            if (data.handle().name().equalsIgnoreCase(name)) {
                return data;
            }
        }
        return null;
    }

    public List<String> namesStartingWith(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (PlayerData data : byUuid.values()) {
            String name = data.handle().name();
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(name);
            }
        }
        return names;
    }

    public void removalListener(Consumer<PlayerData> removalListener) {
        this.removalListener = removalListener == null ? data -> { } : removalListener;
    }

    public void remove(Object connection) {
        PlayerData removed = byConnection.remove(connection);
        if (removed == null) {
            return;
        }
        UUID uuid = removed.handle().uuid();
        if (uuid != null) {
            byUuid.remove(uuid, removed);
        }
        removed.profile(null);
        try {
            removalListener.accept(removed);
        } catch (RuntimeException ignored) {
        }
        // The context is left alone: closing a channel from a check runs this synchronously, while that
        // same context is still in use further up the stack. It holds nothing after each packet anyway.
    }

    /** Removes the entries whose channel is already closed. */
    public int sweep() {
        int removed = 0;
        for (Map.Entry<Object, PlayerData> entry : byConnection.entrySet()) {
            if (!entry.getValue().handle().isOpen()) {
                remove(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    public void clear() {
        byConnection.clear();
        byUuid.clear();
    }

    public int size() {
        return byConnection.size();
    }

    public Collection<PlayerData> all() {
        return byConnection.values();
    }
}
