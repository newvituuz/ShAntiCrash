package br.com.singlehope.shanticrash.player;

import br.com.singlehope.shanticrash.configuration.Messages;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisconnect;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;
import java.util.UUID;

/** Connection operations through PacketEvents. Writing to a channel is safe from any thread. */
public final class UserPlayerHandle implements PlayerHandle {
    private final User user;

    public UserPlayerHandle(User user) {
        this.user = user;
    }

    public User user() {
        return user;
    }

    @Override
    public String name() {
        String name = user.getName();
        if (name != null) {
            return name;
        }
        InetSocketAddress address = user.getAddress();
        return address == null ? "desconhecido" : address.getAddress().getHostAddress();
    }

    @Override
    public UUID uuid() {
        return user.getUUID();
    }

    @Override
    public int clientProtocol() {
        ClientVersion version = user.getClientVersion();
        return version == null ? -1 : version.getProtocolVersion();
    }

    @Override
    public String clientVersion() {
        ClientVersion version = user.getClientVersion();
        return version == null ? "?" : version.getReleaseName();
    }

    @Override
    public void kick(String miniMessage) {
        try {
            Component reason = Messages.parse(miniMessage);
            ConnectionState state = user.getEncoderState();
            PacketWrapper<?> packet = null;
            if (state == ConnectionState.PLAY) {
                packet = new WrapperPlayServerDisconnect(reason);
            } else if (state == ConnectionState.CONFIGURATION) {
                packet = new WrapperConfigServerDisconnect(reason);
            } else if (state == ConnectionState.LOGIN) {
                packet = new WrapperLoginServerDisconnect(reason);
            }
            if (packet != null) {
                user.sendPacket(packet);
            }
        } catch (RuntimeException ignored) {
            // A kick must succeed no matter what state the connection is in. The close below is what counts.
        }
        user.closeConnection();
    }

    @Override
    public void close() {
        user.closeConnection();
    }

    @Override
    public boolean isOpen() {
        return ChannelHelper.isOpen(user.getChannel());
    }

    @Override
    public void sendMessage(Object component) {
        user.sendMessage((Component) component);
    }
}
