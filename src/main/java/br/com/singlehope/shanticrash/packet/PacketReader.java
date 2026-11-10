package br.com.singlehope.shanticrash.packet;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Bounds checked cursor over a frame. Uses absolute reads, so the buffer's reader index is never
 * touched and the frame is delivered to the server exactly as it arrived.
 */
public final class PacketReader {
    private static final MalformedPacketException TRUNCATED = new MalformedPacketException("pacote truncado");
    private static final MalformedPacketException BAD_VARINT = new MalformedPacketException("VarInt inválido");
    private static final MalformedPacketException BAD_STRING = new MalformedPacketException("string inválida");
    private static final MalformedPacketException BAD_LENGTH = new MalformedPacketException("comprimento negativo");

    private ByteBuf buf;
    private int pos;
    private int end;

    public PacketReader reset(ByteBuf buf, int start, int end) {
        this.buf = buf;
        this.pos = start;
        this.end = end;
        return this;
    }

    public void release() {
        this.buf = null;
    }

    public ByteBuf buffer() {
        return buf;
    }

    public int position() {
        return pos;
    }

    public int end() {
        return end;
    }

    public void position(int position) {
        if (position < 0 || position > end) {
            throw TRUNCATED;
        }
        this.pos = position;
    }

    public int remaining() {
        return end - pos;
    }

    public boolean hasRemaining() {
        return pos < end;
    }

    private void require(int bytes) {
        if (bytes < 0 || bytes > end - pos) {
            throw TRUNCATED;
        }
    }

    public void skip(int bytes) {
        require(bytes);
        pos += bytes;
    }

    public int readByte() {
        require(1);
        return buf.getByte(pos++);
    }

    public int readUByte() {
        require(1);
        return buf.getUnsignedByte(pos++);
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public int readShort() {
        require(2);
        int value = buf.getShort(pos);
        pos += 2;
        return value;
    }

    public int readUShort() {
        require(2);
        int value = buf.getUnsignedShort(pos);
        pos += 2;
        return value;
    }

    public int readInt() {
        require(4);
        int value = buf.getInt(pos);
        pos += 4;
        return value;
    }

    public long readLong() {
        require(8);
        long value = buf.getLong(pos);
        pos += 8;
        return value;
    }

    public float readFloat() {
        require(4);
        float value = buf.getFloat(pos);
        pos += 4;
        return value;
    }

    public double readDouble() {
        require(8);
        double value = buf.getDouble(pos);
        pos += 8;
        return value;
    }

    public int readVarInt() {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            require(1);
            byte b = buf.getByte(pos++);
            value |= (b & 0x7F) << shift;
            if (b >= 0) {
                return value;
            }
        }
        throw BAD_VARINT;
    }

    /** Reads a VarInt length and guarantees it fits in the rest of the frame. */
    public int readLength() {
        int length = readVarInt();
        if (length < 0) {
            throw BAD_LENGTH;
        }
        require(length);
        return length;
    }

    /**
     * Reads a string limited to {@code maxChars}. The byte limit is verified before anything is
     * decoded, so an oversized string costs nothing. UTF-8 needs at most 3 bytes per UTF-16 unit.
     */
    public String readString(int maxChars) {
        int length = readLength();
        if (length > (long) maxChars * 3L) {
            throw BAD_STRING;
        }
        String value = buf.toString(pos, length, StandardCharsets.UTF_8);
        pos += length;
        if (value.length() > maxChars) {
            throw BAD_STRING;
        }
        return value;
    }

    /** Skips a VarInt prefixed string and returns its size in bytes. */
    public int skipString() {
        int length = readLength();
        pos += length;
        return length;
    }

    public void expectEnd() {
        if (pos != end) {
            throw new MalformedPacketException((end - pos) + " bytes sobrando");
        }
    }

    /** Size of a value when encoded as a VarInt. */
    public static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
