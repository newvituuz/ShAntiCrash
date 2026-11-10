package br.com.singlehope.shanticrash.integration;

import br.com.singlehope.shanticrash.support.Wire;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A minimal Minecraft client for testing the plugin on a real server.
 *
 * <p>It connects only to the loopback address, on purpose: this is a test tool for a server on the same
 * machine and can not be pointed anywhere else. It needs offline mode and compression turned off, and
 * implements just enough of the protocol to reach the play state and stay there.
 */
final class ProtocolBot implements Closeable {
    private enum State {
        LOGIN,
        CONFIGURATION,
        PLAY
    }

    private final ClientVersion version;
    private final int protocol;
    private final String name;
    private final Socket socket = new Socket();
    private final CountDownLatch inPlay = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Object writeLock = new Object();
    private final CountDownLatch watched = new CountDownLatch(1);
    private DataOutputStream out;
    private volatile String disconnectReason;
    private volatile byte[] watch;
    private volatile int responseId = -1;
    private volatile Runnable responseListener;

    ProtocolBot(ClientVersion version, String name) {
        this.version = version;
        this.protocol = version.getProtocolVersion();
        this.name = name;
    }

    /** Opens the connection, sends the handshake and starts the login. */
    void connect(int port) throws IOException {
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
        send(0x00, Wire.create().v(protocol).str("localhost").s(port).v(2).toBytes());
        send(0x00, loginStart());
    }

    /** Connects and waits for the play state. */
    void join(int port) throws Exception {
        connect(port);
        startReader();
        if (!inPlay.await(30, TimeUnit.SECONDS)) {
            throw new IOException("não entrou no jogo: " + (disconnectReason == null ? "tempo esgotado" : disconnectReason));
        }
        // The join event runs on the next server tick, give it time to be processed.
        Thread.sleep(400L);
    }

    void startReader() {
        Thread reader = new Thread(this::readLoop, "bot-" + name);
        reader.setDaemon(true);
        reader.start();
    }

    private byte[] loginStart() {
        Wire w = Wire.create().str(name);
        if (protocol >= 764) {
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            w.l(uuid.getMostSignificantBits()).l(uuid.getLeastSignificantBits());
        } else if (protocol >= 761) {
            w.bool(false);
        } else if (protocol >= 760) {
            w.bool(false).bool(false);
        } else if (protocol >= 759) {
            w.bool(false);
        }
        return w.toBytes();
    }

    void send(int id, byte[] body) throws IOException {
        synchronized (writeLock) {
            writeFrame(id, body);
            out.flush();
        }
    }

    /** Many frames without flushing between them, to arrive as fast as the socket allows. */
    void sendBurst(int id, byte[] body, int count) throws IOException {
        synchronized (writeLock) {
            for (int i = 0; i < count; i++) {
                writeFrame(id, body);
            }
            out.flush();
        }
    }

    private void writeFrame(int id, byte[] body) throws IOException {
        byte[] idBytes = Wire.create().v(id).toBytes();
        byte[] length = Wire.create().v(idBytes.length + body.length).toBytes();
        out.write(length);
        out.write(idBytes);
        out.write(body);
    }

    /** Sends raw bytes as they are, used for frames with a false length. */
    void sendRaw(byte[] bytes) throws IOException {
        synchronized (writeLock) {
            out.write(bytes);
            out.flush();
        }
    }

    int playId(PacketType.Play.Client type) {
        return type.getId(version);
    }

    /** Waits for this text in any packet received while in play. */
    void watchFor(String text) {
        this.watch = text.getBytes(StandardCharsets.UTF_8);
    }

    boolean awaitWatched(long millis) throws InterruptedException {
        return watched.await(millis, TimeUnit.MILLISECONDS);
    }

    /** Calls the listener whenever a packet of this type arrives, used to measure round trips. */
    void onResponse(PacketType.Play.Server type, Runnable listener) {
        this.responseId = type.getId(version);
        this.responseListener = listener;
    }

    private static boolean contains(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private void readLoop() {
        State state = State.LOGIN;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536))) {
            int finishConfiguration = protocol >= 764 ? PacketType.Configuration.Server.CONFIGURATION_END.getId(version) : -1;
            int configKeepAlive = protocol >= 764 ? PacketType.Configuration.Server.KEEP_ALIVE.getId(version) : -1;
            int configPing = protocol >= 764 ? PacketType.Configuration.Server.PING.getId(version) : -1;
            int configDisconnect = protocol >= 764 ? PacketType.Configuration.Server.DISCONNECT.getId(version) : -1;
            int knownPacks = protocol >= 766 ? PacketType.Configuration.Server.SELECT_KNOWN_PACKS.getId(version) : -1;
            int joinGame = PacketType.Play.Server.JOIN_GAME.getId(version);
            int keepAlive = PacketType.Play.Server.KEEP_ALIVE.getId(version);
            int ping = protocol >= 755 ? PacketType.Play.Server.PING.getId(version) : -1;
            int playDisconnect = PacketType.Play.Server.DISCONNECT.getId(version);
            while (true) {
                int length = readVarInt(in);
                if (length <= 0 || length > 16 * 1024 * 1024) {
                    throw new IOException("frame inválido: " + length);
                }
                byte[] frame = new byte[length];
                in.readFully(frame);
                int[] cursor = {0};
                int id = readVarInt(frame, cursor);
                byte[] body = new byte[length - cursor[0]];
                System.arraycopy(frame, cursor[0], body, 0, body.length);
                switch (state) {
                    case LOGIN:
                        if (id == 0x00) {
                            disconnectReason = "login: " + new String(body, StandardCharsets.UTF_8);
                            return;
                        } else if (id == 0x01) {
                            disconnectReason = "servidor em modo online";
                            return;
                        } else if (id == 0x03) {
                            disconnectReason = "compressão ligada";
                            return;
                        } else if (id == 0x02) {
                            if (protocol >= 764) {
                                send(0x03, new byte[0]);
                                state = State.CONFIGURATION;
                            } else {
                                state = State.PLAY;
                            }
                        } else if (id == 0x04) {
                            // Login plugin request without an answer.
                            int[] c = {0};
                            int message = readVarInt(body, c);
                            send(PacketType.Login.Client.LOGIN_PLUGIN_RESPONSE.getId(version),
                                    Wire.create().v(message).bool(false).toBytes());
                        }
                        break;
                    case CONFIGURATION:
                        if (id == finishConfiguration) {
                            send(PacketType.Configuration.Client.CONFIGURATION_END_ACK.getId(version), new byte[0]);
                            state = State.PLAY;
                        } else if (id == knownPacks) {
                            send(PacketType.Configuration.Client.SELECT_KNOWN_PACKS.getId(version), Wire.create().v(0).toBytes());
                        } else if (id == configKeepAlive) {
                            send(PacketType.Configuration.Client.KEEP_ALIVE.getId(version), body);
                        } else if (id == configPing) {
                            send(PacketType.Configuration.Client.PONG.getId(version), body);
                        } else if (id == configDisconnect) {
                            disconnectReason = "configuração: " + new String(body, StandardCharsets.UTF_8);
                            return;
                        }
                        break;
                    default:
                        byte[] expected = watch;
                        if (expected != null && contains(body, expected)) {
                            watched.countDown();
                        }
                        if (id == responseId && responseListener != null) {
                            responseListener.run();
                        }
                        if (id == joinGame) {
                            inPlay.countDown();
                        } else if (id == keepAlive) {
                            send(PacketType.Play.Client.KEEP_ALIVE.getId(version), body);
                        } else if (id == ping && ping >= 0) {
                            send(PacketType.Play.Client.PONG.getId(version), body);
                        } else if (id == playDisconnect) {
                            disconnectReason = new String(body, StandardCharsets.UTF_8);
                            return;
                        }
                        break;
                }
            }
        } catch (EOFException e) {
            if (disconnectReason == null) {
                disconnectReason = "conexão fechada";
            }
        } catch (IOException e) {
            if (disconnectReason == null) {
                disconnectReason = "conexão fechada: " + e.getMessage();
            }
        } finally {
            closed.countDown();
        }
    }

    boolean isClosed() {
        return closed.getCount() == 0;
    }

    String disconnectReason() {
        return disconnectReason;
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = in.readByte();
            value |= (b & 0x7F) << shift;
            if (b >= 0) {
                return value;
            }
        }
        throw new IOException("VarInt longo demais");
    }

    private static int readVarInt(byte[] data, int[] cursor) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = data[cursor[0]++];
            value |= (b & 0x7F) << shift;
            if (b >= 0) {
                return value;
            }
        }
        throw new IOException("VarInt longo demais");
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing to do about a socket that failed to close at the end of a test.
        }
    }
}
