package br.com.singlehope.shanticrash.violation;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.player.PlayerData;

/** Receives what the {@link ViolationManager} decided. Called on the player's Netty thread. */
public interface ViolationListener {
    void onFlag(Check check, CheckSettings settings, PlayerData data, double vl, String info, long nowNanos);

    void onPunish(Check check, CheckSettings settings, PlayerData data, double vl, String info, long nowNanos);
}
