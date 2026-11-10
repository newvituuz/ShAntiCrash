package br.com.singlehope.shanticrash.support;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Builds packet bodies and NBT by hand, on purpose byte by byte: these tests exist to send what a
 * correct encoder would refuse to produce.
 */
public final class Wire {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bytes);

    public static Wire create() {
        return new Wire();
    }

    public byte[] toBytes() {
        return bytes.toByteArray();
    }

    public int size() {
        return bytes.size();
    }

    public Wire raw(byte... data) {
        try {
            out.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public Wire b(int value) {
        try {
            out.writeByte(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public Wire bool(boolean value) {
        return b(value ? 1 : 0);
    }

    public Wire s(int value) {
        try {
            out.writeShort(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public Wire i(int value) {
        try {
            out.writeInt(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public Wire l(long value) {
        try {
            out.writeLong(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public Wire f(float value) {
        try {
            out.writeFloat(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public Wire d(double value) {
        try {
            out.writeDouble(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public Wire v(int value) {
        while ((value & ~0x7F) != 0) {
            b((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        return b(value);
    }

    public Wire str(String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        v(data.length);
        return raw(data);
    }

    /** VarInt prefixed bytes. */
    public Wire prefixed(byte[] data) {
        v(data.length);
        return raw(data);
    }

    public static String repeat(char c, int times) {
        char[] chars = new char[times];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    // ---------------------------------------------------------------- NBT

    private static final int TAG_BYTE = 1;
    private static final int TAG_INT = 3;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_END = 0;

    private Wire name(String name) {
        byte[] data = name.getBytes(StandardCharsets.UTF_8);
        s(data.length);
        return raw(data);
    }

    /** Opens the root compound in the format of the protocol. */
    public Wire nbtRoot(boolean named) {
        b(TAG_COMPOUND);
        if (named) {
            s(0);
        }
        return this;
    }

    public Wire nbtEnd() {
        return b(TAG_END);
    }

    public Wire nbtByte(String name, int value) {
        b(TAG_BYTE);
        name(name);
        return b(value);
    }

    public Wire nbtInt(String name, int value) {
        b(TAG_INT);
        name(name);
        return i(value);
    }

    public Wire nbtString(String name, String value) {
        b(TAG_STRING);
        name(name);
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        s(data.length);
        return raw(data);
    }

    public Wire nbtOpenCompound(String name) {
        b(TAG_COMPOUND);
        return name(name);
    }

    public Wire nbtByteArray(String name, int declaredLength, int realLength) {
        b(TAG_BYTE_ARRAY);
        name(name);
        i(declaredLength);
        return raw(new byte[realLength]);
    }

    public Wire nbtOpenList(String name, int elementType, int length) {
        b(TAG_LIST);
        name(name);
        b(elementType);
        return i(length);
    }

    public static byte[] nbtEmpty() {
        return new byte[]{0};
    }

    /** A compound holding {@code depth} nested compounds. */
    public static byte[] nbtDeep(int depth, boolean named) {
        Wire w = create().nbtRoot(named);
        for (int i = 1; i < depth; i++) {
            w.nbtOpenCompound("a");
        }
        for (int i = 0; i < depth; i++) {
            w.nbtEnd();
        }
        return w.toBytes();
    }

    public static byte[] nbtManyTags(int tags, boolean named) {
        Wire w = create().nbtRoot(named);
        for (int i = 0; i < tags; i++) {
            w.nbtByte("t", 1);
        }
        return w.nbtEnd().toBytes();
    }

    /** Many nodes in few bytes, using primitive lists. Each list stays below the list limit. */
    public static byte[] nbtManyNodes(int lists, int elements, boolean named) {
        Wire w = create().nbtRoot(named);
        for (int i = 0; i < lists; i++) {
            w.nbtOpenList("l", TAG_BYTE, elements).raw(new byte[elements]);
        }
        return w.nbtEnd().toBytes();
    }

    public static byte[] nbtLongString(int length, boolean named) {
        return create().nbtRoot(named).nbtString("s", repeat('x', length)).nbtEnd().toBytes();
    }

    public static byte[] nbtBigArray(int length, boolean named) {
        return create().nbtRoot(named).nbtByteArray("a", length, length).nbtEnd().toBytes();
    }

    public static byte[] nbtBigList(int length, boolean named) {
        Wire w = create().nbtRoot(named).nbtOpenList("l", TAG_COMPOUND, length);
        for (int i = 0; i < length; i++) {
            w.nbtEnd();
        }
        return w.nbtEnd().toBytes();
    }

    /** Container items inside container items, the way block entity data stores them. */
    public static byte[] nbtNestedContainers(int levels, boolean named) {
        Wire w = create().nbtRoot(named);
        for (int i = 0; i < levels; i++) {
            w.nbtOpenCompound("BlockEntityTag");
            w.nbtOpenList("Items", TAG_COMPOUND, 1);
            w.nbtString("id", "minecraft:chest").nbtByte("Count", 1);
            w.nbtOpenCompound("tag");
        }
        for (int i = 0; i < levels; i++) {
            w.nbtEnd().nbtEnd().nbtEnd();
        }
        return w.nbtEnd().toBytes();
    }

    public static byte[] nbtBook(int pages, int pageLength, boolean named) {
        Wire w = create().nbtRoot(named);
        w.nbtOpenList("pages", TAG_STRING, pages);
        String text = repeat('p', pageLength);
        for (int i = 0; i < pages; i++) {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            w.s(data.length).raw(data);
        }
        return w.nbtEnd().toBytes();
    }

    public static byte[] nbtText(String value, boolean named) {
        return create().nbtRoot(named).nbtString("text", value).nbtEnd().toBytes();
    }
}
