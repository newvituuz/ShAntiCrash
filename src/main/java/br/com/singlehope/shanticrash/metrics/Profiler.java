package br.com.singlehope.shanticrash.metrics;

import br.com.singlehope.shanticrash.player.PlayerData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Profiler {
    public static final class Settings {
        public static final Settings DEFAULT = new Settings(60_000_000_000L, 1, 5);

        public final long durationNanos;
        public final int sampleEvery;
        public final int maxSessions;

        public Settings(long durationNanos, int sampleEvery, int maxSessions) {
            this.durationNanos = Math.max(1_000_000_000L, Math.min(durationNanos, 3_600_000_000_000L));
            this.sampleEvery = Math.max(1, sampleEvery);
            this.maxSessions = Math.max(1, maxSessions);
        }
    }

    private final Map<ProfileSession, PlayerData> active = new ConcurrentHashMap<>();
    private volatile Settings settings = Settings.DEFAULT;

    public void settings(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    public synchronized ProfileSession start(PlayerData data, Object owner, int checks, long nowNanos) {
        ProfileSession previous = data.profile();
        if (previous != null) {
            previous.end(nowNanos);
            active.remove(previous);
        }
        Settings current = settings;
        if (active.size() >= current.maxSessions) {
            return null;
        }
        ProfileSession session = new ProfileSession(data.handle().name(), checks, nowNanos,
                current.durationNanos, current.sampleEvery, owner);
        active.put(session, data);
        data.profile(session);
        return session;
    }

    public synchronized List<ProfileSession> stopAll(long nowNanos) {
        List<ProfileSession> stopped = new ArrayList<>(active.keySet());
        for (ProfileSession session : stopped) {
            finish(session, active.get(session), nowNanos);
        }
        active.clear();
        return stopped;
    }

    public synchronized List<ProfileSession> expire(long nowNanos) {
        List<ProfileSession> expired = new ArrayList<>();
        Iterator<Map.Entry<ProfileSession, PlayerData>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ProfileSession, PlayerData> entry = iterator.next();
            ProfileSession session = entry.getKey();
            if (session.expired(nowNanos) || !entry.getValue().handle().isOpen()) {
                finish(session, entry.getValue(), nowNanos);
                iterator.remove();
                expired.add(session);
            }
        }
        return expired;
    }

    private static void finish(ProfileSession session, PlayerData data, long nowNanos) {
        session.end(nowNanos);
        if (data != null && data.profile() == session) {
            data.profile(null);
        }
    }

    public int activeSessions() {
        return active.size();
    }
}
