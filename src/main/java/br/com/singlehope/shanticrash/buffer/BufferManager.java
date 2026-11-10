package br.com.singlehope.shanticrash.buffer;

import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.violation.CheckState;

/**
 * The tolerance applied before a suspicious event becomes a violation.
 *
 * <p>Each event adds one point and points decay with time, so only events that repeat faster than the
 * decay can reach the maximum. Reaching it multiplies the buffer instead of clearing it, which makes a
 * player who keeps misbehaving reach the next violation sooner than one with an isolated incident.
 */
public final class BufferManager {
    private BufferManager() {
    }

    /**
     * Accounts for one event.
     *
     * @return true if the buffer is full and the event must become a violation
     */
    public static boolean accumulate(CheckState state, CheckSettings settings, long nowNanos) {
        double buffer = state.bufferAfterDecay(settings, nowNanos) + 1.0D;
        if (buffer < settings.bufferMax) {
            state.buffer(buffer, nowNanos);
            return false;
        }
        state.buffer(buffer * settings.bufferMultiply, nowNanos);
        return true;
    }
}
