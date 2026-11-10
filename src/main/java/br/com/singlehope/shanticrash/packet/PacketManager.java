package br.com.singlehope.shanticrash.packet;

import br.com.singlehope.shanticrash.api.event.EventDispatcher;
import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckManager;
import br.com.singlehope.shanticrash.checks.window.WindowChecks;
import br.com.singlehope.shanticrash.configuration.ConfigurationManager;
import br.com.singlehope.shanticrash.configuration.PluginSettings;
import br.com.singlehope.shanticrash.connection.ConnectionLimiter;
import br.com.singlehope.shanticrash.logging.EventLogger;
import br.com.singlehope.shanticrash.metrics.NettyWatchdog;
import br.com.singlehope.shanticrash.metrics.PacketMetrics;
import br.com.singlehope.shanticrash.metrics.ProfileSession;
import br.com.singlehope.shanticrash.packet.decoder.OutgoingItemSanitizer;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.player.PlayerDataManager;
import br.com.singlehope.shanticrash.player.UserPlayerHandle;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserConnectEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.netty.buffer.ByteBuf;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point of every packet. Runs before vanilla decodes the frame, on the connection's Netty thread.
 *
 * <p>Order of work: find the connection state, classify the packet, then run the chain for that kind,
 * stopping at the first check that cancels. Anything thrown by a check stays inside that check: the
 * packet continues through the others and the server never sees the exception.
 */
public final class PacketManager extends PacketListenerAbstract {
    private static final long ERROR_LOG_INTERVAL_NANOS = 60_000_000_000L;
    private static final long CANCEL_LOG_INTERVAL_NANOS = 1_000_000_000L;

    private final CheckManager checks;
    private final PlayerDataManager players;
    private final ConfigurationManager configuration;
    private final PacketMetrics metrics;
    private final Logger logger;
    private final ServerProtocol protocol;
    private final ConnectionLimiter limiter;
    private final OutgoingItemSanitizer sanitizer;
    private final AtomicLong lastErrorLog = new AtomicLong();
    private final AtomicLong lastCapLog = new AtomicLong();
    private final AtomicLong lastLimitLog = new AtomicLong();
    private final AtomicLong lastSanitizerError = new AtomicLong();

    private volatile NettyWatchdog watchdog;
    private volatile EventLogger eventLogger;
    private volatile EventDispatcher events;
    private volatile boolean monitoring;
    private volatile long heavyNanos;

    public PacketManager(CheckManager checks, PlayerDataManager players, ConfigurationManager configuration,
                         PacketMetrics metrics, Logger logger, ServerProtocol protocol,
                         ConnectionLimiter limiter, OutgoingItemSanitizer sanitizer) {
        super(PacketListenerPriority.LOWEST);
        this.checks = checks;
        this.players = players;
        this.configuration = configuration;
        this.metrics = metrics;
        this.logger = logger;
        this.protocol = protocol;
        this.limiter = limiter;
        this.sanitizer = sanitizer;
    }

    /** Reads the settings used on every packet into fields, once per reload. */
    public void refresh(NettyWatchdog watchdog, EventLogger eventLogger) {
        PluginSettings settings = configuration.settings();
        this.monitoring = settings.packetMonitoring;
        this.heavyNanos = settings.heavyPacketNanos;
        this.watchdog = watchdog;
        this.eventLogger = eventLogger;
    }

    public void events(EventDispatcher events) {
        this.events = events;
    }

    public ServerProtocol protocol() {
        return protocol;
    }

    private static InetAddress address(User user) {
        InetSocketAddress socket = user.getAddress();
        return socket == null ? null : socket.getAddress();
    }

    @Override
    public void onUserConnect(UserConnectEvent event) {
        User user = event.getUser();
        InetAddress address = address(user);
        ConnectionLimiter.Result limit = limiter.acquire(address);
        if (limit == ConnectionLimiter.Result.REJECTED) {
            event.setCancelled(true);
            metrics.connectionRejected();
            long now = System.nanoTime();
            long last = lastLimitLog.get();
            EventLogger log = eventLogger;
            if (log != null && now - last > CANCEL_LOG_INTERVAL_NANOS && lastLimitLog.compareAndSet(last, now)) {
                log.log("Conexão recusada: limite de " + limiter.settings().maxPerAddress
                        + " conexões simultâneas por endereço atingido");
            }
            return;
        }
        PlayerData data = players.create(user, new UserPlayerHandle(user));
        if (data == null) {
            if (limit == ConnectionLimiter.Result.ALLOWED) {
                limiter.release(address);
            }
            event.setCancelled(true);
            long now = System.nanoTime();
            long last = lastCapLog.get();
            if (now - last > ERROR_LOG_INTERVAL_NANOS && lastCapLog.compareAndSet(last, now)) {
                logger.warning("Limite performance.max-player-data atingido, recusando novas conexões.");
            }
            return;
        }
        if (limit == ConnectionLimiter.Result.ALLOWED) {
            if (data.limitedAddress() == null) {
                data.limitedAddress(address);
            } else {
                limiter.release(address);
            }
        }
    }

    @Override
    public void onUserLogin(UserLoginEvent event) {
        User user = event.getUser();
        if (user == null) {
            return;
        }
        UUID uuid = user.getUUID();
        PlayerData data = players.get(user);
        if (uuid != null && data != null) {
            players.bindUuid(uuid, data);
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        players.remove(event.getUser());
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        try {
            handle(event);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            reportError(null, t);
        }
    }

    private void handle(PacketReceiveEvent event) {
        User user = event.getUser();
        PlayerData data = players.get(user);
        if (data == null) {
            if (!ChannelHelper.isOpen(user.getChannel())) {
                event.setCancelled(true);
                return;
            }
            data = players.create(user, new UserPlayerHandle(user));
            if (data == null) {
                event.setCancelled(true);
                return;
            }
        }
        if (data.isClosing()) {
            event.setCancelled(true);
            return;
        }
        if (data.isBypass()) {
            return;
        }

        PacketTypeCommon type = event.getPacketType();
        PacketKind kind = PacketKinds.of(type);
        Object rawBuffer = event.getByteBuf();
        if (!(rawBuffer instanceof ByteBuf)) {
            metrics.uninspected();
            return;
        }
        ByteBuf buffer = (ByteBuf) rawBuffer;
        long now = System.nanoTime();
        int bodyStart = buffer.readerIndex();
        int bodyEnd = buffer.writerIndex();
        int frameSize = bodyEnd - bodyStart + PacketReader.varIntSize(event.getPacketId());

        PacketContext context = data.context().prepare(kind, buffer, bodyStart, bodyEnd, frameSize, now,
                protocol, data, event);
        ProfileSession profile = data.profile();
        if (profile != null) {
            if (profile.expired(now)) {
                profile = null;
            } else {
                context.profileSampled = profile.sample();
            }
        }
        NettyWatchdog activeWatchdog = watchdog;
        if (activeWatchdog != null) {
            activeWatchdog.enter(now);
        }
        try {
            PacketPipeline.run(context, checks.chain(kind), checks.malformed, this::reportError);
        } finally {
            if (activeWatchdog != null) {
                activeWatchdog.exit();
            }
            finish(event, context, kind, now, profile);
        }
    }

    private void finish(PacketReceiveEvent event, PacketContext context, PacketKind kind, long startNanos,
                        ProfileSession profile) {
        boolean cancelled = context.isCancelled();
        PlayerData data = context.data;
        if (cancelled) {
            event.setCancelled(true);
            metrics.cancelled();
            logCancelled(context, kind, startNanos);
            EventDispatcher dispatcher = events;
            if (dispatcher != null && startNanos - data.lastBlockedEventNanos >= CANCEL_LOG_INTERVAL_NANOS) {
                data.lastBlockedEventNanos = startNanos;
                dispatcher.blocked(data, kind.name(), context.cancelledBy, context.frameSize);
            }
        }
        metrics.packet();
        if (data.isDebug()) {
            logDebug(context, kind, cancelled);
        }
        boolean timeIt = monitoring || (profile != null && context.profileSampled);
        long elapsed = timeIt ? System.nanoTime() - startNanos : -1L;
        if (profile != null) {
            profile.packet(kind, context.frameSize, cancelled, context.profileSampled ? elapsed : -1L);
        }
        if (monitoring) {
            metrics.timing(kind, elapsed);
            if (heavyNanos > 0L && elapsed > heavyNanos) {
                recordHeavyPacket(context, kind, elapsed);
            }
        }
        context.clear();
    }

    private void logCancelled(PacketContext context, PacketKind kind, long nowNanos) {
        EventLogger log = eventLogger;
        PlayerData data = context.data;
        if (log == null || !configuration.settings().logCancelledPackets
                || nowNanos - data.lastCancelLogNanos < CANCEL_LOG_INTERVAL_NANOS) {
            return;
        }
        data.lastCancelLogNanos = nowNanos;
        log.log("Pacote cancelado: " + kind + " de " + data.handle().name() + ", " + context.frameSize + " bytes");
    }

    private void logDebug(PacketContext context, PacketKind kind, boolean cancelled) {
        EventLogger log = eventLogger;
        if (log != null) {
            log.log("Debug " + context.data.handle().name() + ": " + kind + " " + context.frameSize
                    + " bytes" + (cancelled ? " cancelado" : ""));
        }
    }

    private void recordHeavyPacket(PacketContext context, PacketKind kind, long elapsed) {
        EventLogger log = eventLogger;
        if (log != null) {
            log.log("Pacote pesado: " + kind + " de " + context.data.handle().name()
                    + " protocolo " + context.data.handle().clientProtocol()
                    + " " + elapsed / 1000L + " us, " + context.frameSize + " bytes");
        }
        checks.processingTime.report(context, elapsed);
    }

    private void reportError(Check check, Throwable error) {
        metrics.checkError(check == null ? -1 : check.index());
        long now = System.nanoTime();
        long last = lastErrorLog.get();
        if (now - last > ERROR_LOG_INTERVAL_NANOS && lastErrorLog.compareAndSet(last, now)) {
            logger.log(Level.WARNING, "Erro isolado em " + (check == null ? "PacketManager" : check.id())
                    + ". Mais erros serão suprimidos por um minuto.", error);
        }
    }

    /**
     * Watches what the server tells the client about windows. The slot count of the content packet is
     * the real size of the window, including menus made by plugins and any custom inventory.
     */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        boolean windowItems = type == PacketType.Play.Server.WINDOW_ITEMS;
        if (windowItems || type == PacketType.Play.Server.CLOSE_WINDOW || type == PacketType.Play.Server.OPEN_WINDOW) {
            track(event, type);
        }
        if (sanitizer.enabled() && protocol.componentItems()) {
            if (windowItems) {
                sanitizeWindow(event);
            } else if (type == PacketType.Play.Server.SET_SLOT) {
                sanitizeSlot(event);
            } else if (type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
                sanitizePlayerInventory(event);
            }
        }
    }

    private void track(PacketSendEvent event, PacketTypeCommon type) {
        try {
            PlayerData data = players.get(event.getUser());
            if (data == null) {
                return;
            }
            if (type == PacketType.Play.Server.WINDOW_ITEMS) {
                ByteBuf buffer = (ByteBuf) event.getByteBuf();
                PacketReader reader = new PacketReader().reset(buffer, buffer.readerIndex(), buffer.writerIndex());
                int windowId = protocol.v1_21_2 ? reader.readVarInt() : reader.readUByte();
                if (protocol.v1_17_1) {
                    reader.readVarInt();
                }
                int slots = protocol.v1_17_1 ? reader.readVarInt() : reader.readShort();
                WindowChecks.trackWindowContents(data, windowId, slots);
            } else {
                WindowChecks.trackWindowClosed(data);
            }
        } catch (RuntimeException e) {
            metrics.checkError(-1);
        }
    }

    private void sanitizeWindow(PacketSendEvent event) {
        try {
            WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
            boolean changed = false;
            List<ItemStack> items = wrapper.getItems();
            List<ItemStack> clean = sanitizer.sanitize(items);
            if (clean != items) {
                wrapper.setItems(clean);
                changed = true;
            }
            Optional<ItemStack> carried = wrapper.getCarriedItem();
            if (carried.isPresent()) {
                ItemStack cleanCarried = sanitizer.sanitize(carried.get());
                if (cleanCarried != carried.get()) {
                    wrapper.setCarriedItem(cleanCarried);
                    changed = true;
                }
            }
            if (changed) {
                event.markForReEncode(true);
            }
        } catch (RuntimeException e) {
            sanitizerFailed(e);
        }
    }

    private void sanitizeSlot(PacketSendEvent event) {
        try {
            WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
            ItemStack item = wrapper.getItem();
            ItemStack clean = sanitizer.sanitize(item);
            if (clean != item) {
                wrapper.setItem(clean);
                event.markForReEncode(true);
            }
        } catch (RuntimeException e) {
            sanitizerFailed(e);
        }
    }

    private void sanitizePlayerInventory(PacketSendEvent event) {
        try {
            WrapperPlayServerSetPlayerInventory wrapper = new WrapperPlayServerSetPlayerInventory(event);
            ItemStack item = wrapper.getStack();
            ItemStack clean = sanitizer.sanitize(item);
            if (clean != item) {
                wrapper.setStack(clean);
                event.markForReEncode(true);
            }
        } catch (RuntimeException e) {
            sanitizerFailed(e);
        }
    }

    private void sanitizerFailed(RuntimeException error) {
        metrics.checkError(-1);
        long now = System.nanoTime();
        long last = lastSanitizerError.get();
        if (now - last > ERROR_LOG_INTERVAL_NANOS && lastSanitizerError.compareAndSet(last, now)) {
            logger.log(Level.WARNING, "Falha ao sanitizar itens enviados; o pacote seguiu sem alteração.", error);
        }
    }

    public static void register(PacketManager manager) {
        PacketEvents.getAPI().getEventManager().registerListener(manager);
    }

    public static void unregister(PacketManager manager) {
        PacketEvents.getAPI().getEventManager().unregisterListener(manager);
    }
}
