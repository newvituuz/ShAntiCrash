package br.com.singlehope.shanticrash.packet.decoder;

import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.item.ItemReport;
import br.com.singlehope.shanticrash.item.NbtScanner;
import br.com.singlehope.shanticrash.packet.MalformedPacketException;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.ByteBuf;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * A wrapper whose collection readers refuse sizes the frame can not possibly hold.
 *
 * <p>PacketEvents allocates collections with the size declared on the wire before reading any element.
 * A VarInt close to 2^31 would then reserve gigabytes from a packet of a few bytes. Each element uses
 * at least one byte, so the bytes left in the frame are a safe upper bound.
 */
final class BoundedPacketWrapper extends PacketWrapper<BoundedPacketWrapper> {
    /** Thrown when an NBT tag breaks a limit. The reason is already in the report. */
    static final class LimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        static final LimitExceeded INSTANCE = new LimitExceeded();

        private LimitExceeded() {
            super(null, null, false, false);
        }
    }

    private static final MalformedPacketException IMPOSSIBLE_SIZE =
            new MalformedPacketException("coleção maior que o pacote");

    private final NbtScanner scanner;
    private final ItemLimits limits;
    private final ItemReport report;
    private int itemDepth = 1;

    BoundedPacketWrapper(PacketReceiveEvent event, NbtScanner scanner, ItemLimits limits, ItemReport report) {
        super(event, false);
        this.scanner = scanner;
        this.limits = limits;
        this.report = report;
    }

    /** Nesting level of the item whose component is being read. */
    void itemDepth(int depth) {
        this.itemDepth = depth;
    }

    /**
     * Every NBT read by a component ends here. The limits are checked on the bytes, before PacketEvents
     * reads the tag recursively and into a map, where a repeated name would hide the tags it replaced.
     * The server spends time on each tag on the wire, so that is what has to be counted.
     */
    private void scanNbt() {
        ByteBuf bytes = (ByteBuf) buffer;
        if (scanner.scanAnyRoot(bytes, bytes.readerIndex(), bytes.writerIndex(), false, itemDepth, limits, report) < 0) {
            throw LimitExceeded.INSTANCE;
        }
    }

    @Override
    public NBT readNBTRaw() {
        scanNbt();
        return super.readNBTRaw();
    }

    @Override
    public NBT readUnlimitedNBTRaw() {
        scanNbt();
        return super.readUnlimitedNBTRaw();
    }

    int readable() {
        return ByteBufHelper.readableBytes(buffer);
    }

    int readerIndex() {
        return ByteBufHelper.readerIndex(buffer);
    }

    private int verified(int size) {
        if (size < 0 || size > readable()) {
            throw IMPOSSIBLE_SIZE;
        }
        return size;
    }

    @Override
    public <K, C extends Collection<K>> C readFixedCollection(IntFunction<C> function, Reader<K> reader, int size) {
        return super.readFixedCollection(function, reader, verified(size));
    }

    @Override
    public <K, V> Map<K, V> readMap(Reader<K> keyFunction, Reader<V> valueFunction) {
        return readMap(keyFunction, valueFunction, Integer.MAX_VALUE);
    }

    @Override
    public <K, V> Map<K, V> readMap(Reader<K> keyFunction, Reader<V> valueFunction, int maxSize) {
        int size = verified(readVarInt());
        if (size > maxSize) {
            throw IMPOSSIBLE_SIZE;
        }
        Map<K, V> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            K key = keyFunction.apply(this);
            V value = valueFunction.apply(this);
            map.put(key, value);
        }
        return map;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K> K[] readArray(Reader<K> reader, Class<K> clazz) {
        int length = verified(readVarInt());
        K[] array = (K[]) Array.newInstance(clazz, length);
        for (int i = 0; i < length; i++) {
            array[i] = reader.apply(this);
        }
        return array;
    }
}
