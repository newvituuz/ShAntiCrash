package br.com.singlehope.shanticrash.packet;

/** The fixed part of the click packet, which precedes its items or hashes. */
public final class ClickHeader {
    public int windowId;
    public int slot;
    public int button;
    public int mode;
    public int bodyOffset;

    void parse(PacketReader reader, ServerProtocol protocol) {
        windowId = protocol.v1_21_2 ? reader.readVarInt() : reader.readUByte();
        if (protocol.v1_17_1) {
            reader.readVarInt();
        }
        slot = reader.readShort();
        button = reader.readByte();
        if (!protocol.v1_17) {
            reader.readShort();
        }
        mode = reader.readVarInt();
        bodyOffset = reader.position();
    }
}
