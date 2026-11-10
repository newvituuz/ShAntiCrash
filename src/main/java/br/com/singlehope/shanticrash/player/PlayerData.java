package br.com.singlehope.shanticrash.player;

import br.com.singlehope.shanticrash.flood.FloodState;
import br.com.singlehope.shanticrash.flood.TokenBucket;
import br.com.singlehope.shanticrash.flood.WindowRuleState;
import br.com.singlehope.shanticrash.metrics.ProfileSession;
import br.com.singlehope.shanticrash.protection.RiskScore;
import br.com.singlehope.shanticrash.packet.FloodGroup;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.violation.CheckState;
import br.com.singlehope.shanticrash.violation.ViolationHistory;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * State of one connection. It holds no reference to Bukkit objects, so it can never keep a player or
 * world alive, and it is thrown away as soon as the connection closes.
 *
 * <p>Fields without {@code volatile} belong to the connection's Netty thread. The volatile ones are
 * written on the server side (join, game mode change) and read by the Netty thread.
 */
public final class PlayerData {
    private final PlayerHandle handle;
    private final long connectedAtMillis = System.currentTimeMillis();
    private final PacketContext context = new PacketContext();
    private final AtomicReferenceArray<CheckState> states;
    private final FloodState[] floodStates = new FloodState[FloodGroup.values().length];
    private final WindowRuleState[] windowRuleStates = new WindowRuleState[FloodGroup.values().length];
    private final ViolationHistory history;
    private final RiskScore risk = new RiskScore();

    private volatile boolean behavioralExempt;
    private volatile ProfileSession profile;
    private volatile InetAddress limitedAddress;
    private volatile boolean bypass;
    private volatile boolean bedrock;
    private volatile boolean creative;
    private volatile long gameModeChangedNanos;
    private volatile boolean closing;
    private volatile boolean debug;
    private volatile boolean alertsPermitted;
    private volatile boolean alertsEnabled;
    private volatile boolean printerAlertsPermitted;
    private volatile boolean joined;
    private volatile double floodMultiplier = 1.0D;
    private volatile boolean printerMode;
    private volatile boolean printerForced;
    private final Set<String> mutedCategories = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // Netty thread only.
    public final TokenBucket globalBucket = new TokenBucket();
    public final TokenBucket byteBucket = new TokenBucket();
    public final TokenBucket bigFrameBucket = new TokenBucket();
    public final TokenBucket prePlayBucket = new TokenBucket();
    public final FloodState globalFlood = new FloodState();
    public final FloodState byteFlood = new FloodState();
    public final FloodState bigFrameFlood = new FloodState();

    public int openWindowId = -1;
    public int openWindowSlots = -1;
    public int playerWindowSlots = -1;
    public boolean dragging;
    public int dragSlots;
    public int brandPackets;
    public int registeredChannels;
    public int handshakes;
    public int loginStarts;
    public long lastCancelLogNanos;
    public long lastBlockedEventNanos = Long.MIN_VALUE / 2;

    public long printerWindowStart;
    public int printerWindowPlacements;
    public int printerSustainedWindows;
    public long printerLastPlacement;

    public long lastPunishNanos;

    public PlayerData(PlayerHandle handle, int checkCount, int historySize) {
        this.handle = handle;
        this.states = new AtomicReferenceArray<>(checkCount);
        this.history = new ViolationHistory(historySize);
    }

    public PlayerHandle handle() {
        return handle;
    }

    public long connectedAtMillis() {
        return connectedAtMillis;
    }

    public PacketContext context() {
        return context;
    }

    /** State of a check, created on first use: most players never trigger most checks. */
    public CheckState state(int checkIndex) {
        CheckState state = states.get(checkIndex);
        if (state == null) {
            state = new CheckState();
            states.set(checkIndex, state);
        }
        return state;
    }

    /** State of a check without creating it, for readers on other threads. */
    public CheckState peekState(int checkIndex) {
        return states.get(checkIndex);
    }

    public int stateCount() {
        return states.length();
    }

    public FloodState floodState(FloodGroup group) {
        int index = group.ordinal();
        FloodState state = floodStates[index];
        if (state == null) {
            state = new FloodState();
            floodStates[index] = state;
        }
        return state;
    }

    /** Called only on this connection's Netty thread. */
    public WindowRuleState windowRuleState(FloodGroup group) {
        int index = group.ordinal();
        WindowRuleState state = windowRuleStates[index];
        if (state == null) {
            state = new WindowRuleState();
            windowRuleStates[index] = state;
        }
        return state;
    }

    public ViolationHistory history() {
        return history;
    }

    public RiskScore risk() {
        return risk;
    }

    public double riskRateFactor(long nowNanos) {
        return risk.rateFactor(nowNanos);
    }

    public boolean isBehavioralExempt() {
        return behavioralExempt;
    }

    public void behavioralExempt(boolean behavioralExempt) {
        this.behavioralExempt = behavioralExempt;
    }

    public ProfileSession profile() {
        return profile;
    }

    public void profile(ProfileSession profile) {
        this.profile = profile;
    }

    public InetAddress limitedAddress() {
        return limitedAddress;
    }

    public void limitedAddress(InetAddress limitedAddress) {
        this.limitedAddress = limitedAddress;
    }

    public long approximateBytes() {
        long bytes = 512L + states.length() * 8L;
        for (int i = 0; i < states.length(); i++) {
            if (states.get(i) != null) {
                bytes += 64L;
            }
        }
        for (FloodState state : floodStates) {
            if (state != null) {
                bytes += 64L;
            }
        }
        for (WindowRuleState state : windowRuleStates) {
            if (state != null) {
                bytes += state.approximateBytes();
            }
        }
        return bytes + history.approximateBytes();
    }

    public boolean isBypass() {
        return bypass;
    }

    public void bypass(boolean bypass) {
        this.bypass = bypass;
    }

    public boolean isBedrock() {
        return bedrock;
    }

    public void bedrock(boolean bedrock) {
        this.bedrock = bedrock;
    }

    public boolean isCreative() {
        return creative;
    }

    public void creative(boolean creative, long nowNanos) {
        this.creative = creative;
        this.gameModeChangedNanos = nowNanos;
    }

    public long gameModeChangedNanos() {
        return gameModeChangedNanos;
    }

    public boolean isClosing() {
        return closing;
    }

    public void closing(boolean closing) {
        this.closing = closing;
    }

    public boolean isDebug() {
        return debug;
    }

    public void debug(boolean debug) {
        this.debug = debug;
    }

    public boolean isAlertsPermitted() {
        return alertsPermitted;
    }

    public void alertsPermitted(boolean alertsPermitted) {
        this.alertsPermitted = alertsPermitted;
    }

    public boolean isAlertsEnabled() {
        return alertsEnabled;
    }

    public void alertsEnabled(boolean alertsEnabled) {
        this.alertsEnabled = alertsEnabled;
    }

    public boolean isPrinterAlertsPermitted() {
        return printerAlertsPermitted;
    }

    public void printerAlertsPermitted(boolean printerAlertsPermitted) {
        this.printerAlertsPermitted = printerAlertsPermitted;
    }

    public boolean isJoined() {
        return joined;
    }

    public void joined(boolean joined) {
        this.joined = joined;
    }

    public double floodMultiplier() {
        return floodMultiplier;
    }

    public void floodMultiplier(double floodMultiplier) {
        this.floodMultiplier = floodMultiplier;
    }

    public boolean isPrinterMode() {
        return printerMode || printerForced;
    }

    public boolean isPrinterForced() {
        return printerForced;
    }

    public void printerMode(boolean printerMode) {
        this.printerMode = printerMode;
    }

    public void printerForced(boolean printerForced) {
        this.printerForced = printerForced;
    }

    public Set<String> mutedCategories() {
        return mutedCategories;
    }
}
