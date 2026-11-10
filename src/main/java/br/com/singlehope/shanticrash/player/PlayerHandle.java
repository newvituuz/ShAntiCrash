package br.com.singlehope.shanticrash.player;

import java.util.UUID;

/**
 * What the protection core needs from a connection. Implemented on top of PacketEvents; every method
 * can be called from any thread and none of them touch server state.
 */
public interface PlayerHandle {
    /** Name known by the server, or the remote address before login. */
    String name();

    UUID uuid();

    int clientProtocol();

    /** Client version name, for diagnostics. */
    String clientVersion();

    /** Sends a disconnect message appropriate for the connection state and closes the channel. */
    void kick(String miniMessage);

    /** Closes the channel without telling the client why. */
    void close();

    /** Whether the underlying channel is still open. */
    boolean isOpen();

    /** Sends a formatted chat message. */
    void sendMessage(Object component);
}
