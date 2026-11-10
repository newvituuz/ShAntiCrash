package br.com.singlehope.shanticrash.support;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.EventCreationUtil;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.UUID;

/**
 * Just enough of PacketEvents to use its real registries and wrappers in a unit test. The server version
 * can be changed per test, since registries already keep their data per version.
 */
public final class TestPacketEvents {
    private static volatile ServerVersion version = ServerVersion.V_1_20_5;
    private static boolean installed;

    private TestPacketEvents() {
    }

    public static synchronized void install(ServerVersion serverVersion) {
        version = serverVersion;
        if (installed) {
            return;
        }
        installed = true;
        PacketEvents.setAPI(new PacketEventsAPI<Object>() {
            private final NettyManager netty = new NettyManagerImpl();
            private final ServerManager server = () -> version;

            @Override
            public boolean isLoaded() {
                return true;
            }

            @Override
            public void init() {
            }

            @Override
            public boolean isInitialized() {
                return true;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public Object getPlugin() {
                return null;
            }

            @Override
            public ServerManager getServerManager() {
                return server;
            }

            @Override
            public ProtocolManager getProtocolManager() {
                return null;
            }

            @Override
            public PlayerManager getPlayerManager() {
                return null;
            }

            @Override
            public NettyManager getNettyManager() {
                return netty;
            }

            @Override
            public ChannelInjector getInjector() {
                return null;
            }
        });
    }

    /** Builds a real receive event from a frame that starts with the packet id. */
    public static PacketReceiveEvent event(ByteBuf frame, ServerVersion serverVersion) throws Exception {
        install(serverVersion);
        ClientVersion client = serverVersion.toClientVersion();
        User user = new User(new EmbeddedChannel(), ConnectionState.PLAY, client, new UserProfile(UUID.randomUUID(), "teste"));
        user.setDecoderState(ConnectionState.PLAY);
        return EventCreationUtil.createReceiveEvent(user.getChannel(), user, null, frame, true);
    }
}
