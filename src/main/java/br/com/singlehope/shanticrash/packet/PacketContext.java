package br.com.singlehope.shanticrash.packet;

import br.com.singlehope.shanticrash.item.ItemReport;
import br.com.singlehope.shanticrash.player.PlayerData;
import io.netty.buffer.ByteBuf;

/**
 * Everything the checks need to know about the frame being processed.
 *
 * <p>One instance lives inside each {@link PlayerData} and is reused for every packet of that
 * connection. Packets of one connection are handled sequentially by its Netty thread, so no
 * synchronization is needed and the hot path does not allocate.
 */
public final class PacketContext {
    public PacketKind kind;
    public ByteBuf buffer;
    public int bodyStart;
    public int bodyEnd;
    public int frameSize;
    public long nowNanos;
    public ServerProtocol protocol;
    public PlayerData data;
    /** The PacketEvents event. Only the classes in {@code packet.decoder} look at it. */
    public Object event;
    public boolean profileSampled;
    public String cancelledBy;

    private final PacketReader reader = new PacketReader();
    private final ClickHeader click = new ClickHeader();
    private boolean clickParsed;
    private ItemReport itemReport;
    private boolean cancelled;

    public PacketContext prepare(PacketKind kind, ByteBuf buffer, int bodyStart, int bodyEnd, int frameSize,
                                 long nowNanos, ServerProtocol protocol, PlayerData data, Object event) {
        this.kind = kind;
        this.buffer = buffer;
        this.bodyStart = bodyStart;
        this.bodyEnd = bodyEnd;
        this.frameSize = frameSize;
        this.nowNanos = nowNanos;
        this.protocol = protocol;
        this.data = data;
        this.event = event;
        this.clickParsed = false;
        this.itemReport = null;
        this.cancelled = false;
        this.profileSampled = false;
        this.cancelledBy = null;
        this.reader.reset(buffer, bodyStart, bodyEnd);
        return this;
    }

    /** Drops every reference to netty buffers once the packet has been handled. */
    public void clear() {
        this.buffer = null;
        this.event = null;
        this.itemReport = null;
        this.reader.release();
    }

    /** The shared cursor, at whatever position the previous read left it. */
    public PacketReader reader() {
        return reader;
    }

    /** The shared cursor moved back to the first byte after the packet id. */
    public PacketReader read() {
        return reader.reset(buffer, bodyStart, bodyEnd);
    }

    public int bodySize() {
        return bodyEnd - bodyStart;
    }

    public void cancel() {
        this.cancelled = true;
    }

    /** For reports about a packet that turned out to be acceptable after all. */
    public void uncancel() {
        this.cancelled = false;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public ItemReport itemReport() {
        return itemReport;
    }

    public void itemReport(ItemReport report) {
        this.itemReport = report;
    }

    /** The click packet header, parsed once no matter how many checks need it. */
    public ClickHeader click() {
        if (!clickParsed) {
            click.parse(read(), protocol);
            clickParsed = true;
        }
        return click;
    }
}
