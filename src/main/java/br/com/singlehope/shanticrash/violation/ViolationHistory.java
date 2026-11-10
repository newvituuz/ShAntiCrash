package br.com.singlehope.shanticrash.violation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fixed size ring of the latest violations of one connection, so a long lived attacker cannot grow it.
 * Written by the Netty thread and read by commands, hence the synchronized methods. The lock is per
 * player and is only taken when a violation happens.
 */
public final class ViolationHistory {
    /** One recorded violation. */
    public static final class Entry {
        public final long timeMillis;
        public final String check;
        public final double vl;
        public final String info;

        Entry(long timeMillis, String check, double vl, String info) {
            this.timeMillis = timeMillis;
            this.check = check;
            this.vl = vl;
            this.info = info;
        }
    }

    private final Entry[] entries;
    private int next;
    private int size;

    public ViolationHistory(int capacity) {
        this.entries = new Entry[Math.max(1, capacity)];
    }

    public synchronized void add(long timeMillis, String check, double vl, String info) {
        entries[next] = new Entry(timeMillis, check, vl, info);
        next = (next + 1) % entries.length;
        if (size < entries.length) {
            size++;
        }
    }

    public synchronized long approximateBytes() {
        long bytes = 16L + entries.length * 8L;
        for (int i = 0; i < size; i++) {
            Entry entry = entries[i];
            if (entry != null) {
                bytes += 48L + 2L * (entry.info == null ? 0 : entry.info.length());
            }
        }
        return bytes;
    }

    /** Oldest first. */
    public synchronized List<Entry> snapshot() {
        if (size == 0) {
            return Collections.emptyList();
        }
        List<Entry> copy = new ArrayList<>(size);
        int first = size < entries.length ? 0 : next;
        for (int i = 0; i < size; i++) {
            copy.add(entries[(first + i) % entries.length]);
        }
        return copy;
    }
}
