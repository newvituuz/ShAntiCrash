package br.com.singlehope.shanticrash.packet;

/**
 * Wire format switches for the server protocol.
 *
 * <p>PacketEvents sits after ViaVersion in the pipeline, so frames always use the server's format,
 * regardless of the client version. Only one instance exists per server, which is why the flags are
 * resolved once instead of being compared on every packet.
 */
public final class ServerProtocol {
    public static final int V1_8 = 47;
    public static final int V1_9 = 107;
    public static final int V1_11 = 315;
    public static final int V1_12 = 335;
    public static final int V1_13 = 393;
    public static final int V1_13_2 = 404;
    public static final int V1_14 = 477;
    public static final int V1_17 = 755;
    public static final int V1_17_1 = 756;
    public static final int V1_19 = 759;
    public static final int V1_19_3 = 761;
    public static final int V1_20 = 763;
    public static final int V1_20_2 = 764;
    public static final int V1_20_5 = 766;
    public static final int V1_21 = 767;
    public static final int V1_21_2 = 768;
    public static final int V1_21_4 = 769;
    public static final int V1_21_5 = 770;
    public static final int V26_1 = 775;
    public static final int V26_3 = 777;

    public final int protocol;
    public final boolean v26_1;
    public final boolean v26_3;
    public final boolean v1_9;
    public final boolean v1_11;
    public final boolean v1_12;
    public final boolean v1_13;
    public final boolean v1_13_2;
    public final boolean v1_14;
    public final boolean v1_17;
    public final boolean v1_17_1;
    public final boolean v1_19;
    public final boolean v1_19_3;
    public final boolean v1_20;
    public final boolean v1_20_2;
    public final boolean v1_20_5;
    public final boolean v1_21;
    public final boolean v1_21_2;
    public final boolean v1_21_4;
    public final boolean v1_21_5;

    private ServerProtocol(int protocol) {
        this.protocol = protocol;
        this.v26_1 = protocol >= V26_1;
        this.v26_3 = protocol >= V26_3;
        this.v1_9 = protocol >= V1_9;
        this.v1_11 = protocol >= V1_11;
        this.v1_12 = protocol >= V1_12;
        this.v1_13 = protocol >= V1_13;
        this.v1_13_2 = protocol >= V1_13_2;
        this.v1_14 = protocol >= V1_14;
        this.v1_17 = protocol >= V1_17;
        this.v1_17_1 = protocol >= V1_17_1;
        this.v1_19 = protocol >= V1_19;
        this.v1_19_3 = protocol >= V1_19_3;
        this.v1_20 = protocol >= V1_20;
        this.v1_20_2 = protocol >= V1_20_2;
        this.v1_20_5 = protocol >= V1_20_5;
        this.v1_21 = protocol >= V1_21;
        this.v1_21_2 = protocol >= V1_21_2;
        this.v1_21_4 = protocol >= V1_21_4;
        this.v1_21_5 = protocol >= V1_21_5;
    }

    public static ServerProtocol of(int protocol) {
        return new ServerProtocol(protocol);
    }

    /** Item NBT uses the nameless root introduced by 1.20.2. */
    public boolean namelessNbtRoot() {
        return v1_20_2;
    }

    /** Whether items in this protocol are data component based. */
    public boolean componentItems() {
        return v1_20_5;
    }

    /** Whether click packets only carry hashes instead of full items. */
    public boolean hashedClicks() {
        return v1_21_5;
    }
}
