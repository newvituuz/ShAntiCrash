package br.com.singlehope.shanticrash.support;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckManager;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.checks.item.DecodingGuard;
import br.com.singlehope.shanticrash.item.ItemAnalyzer;
import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.item.ModernItemReader;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketPipeline;
import br.com.singlehope.shanticrash.packet.ServerProtocol;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.player.PlayerHandle;
import br.com.singlehope.shanticrash.violation.ViolationListener;
import br.com.singlehope.shanticrash.violation.ViolationManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** The real chain, the real checks and the real violation rules, without a server. */
public final class TestHarness {
    /** A connection that only records what happened to it. */
    public static final class FakeHandle implements PlayerHandle {
        public final AtomicBoolean kicked = new AtomicBoolean();
        public final AtomicBoolean closed = new AtomicBoolean();
        private final UUID uuid = UUID.randomUUID();

        @Override
        public String name() {
            return "teste";
        }

        @Override
        public UUID uuid() {
            return uuid;
        }

        @Override
        public int clientProtocol() {
            return 47;
        }

        @Override
        public String clientVersion() {
            return "teste";
        }

        @Override
        public void kick(String miniMessage) {
            kicked.set(true);
        }

        @Override
        public void close() {
            closed.set(true);
        }

        @Override
        public boolean isOpen() {
            return !closed.get() && !kicked.get();
        }

        @Override
        public void sendMessage(Object component) {
        }
    }

    /** Stores everything reported by the checks. */
    public static final class Recorder implements ViolationListener {
        public final List<String> flags = Collections.synchronizedList(new ArrayList<>());
        public final List<String> punishments = Collections.synchronizedList(new ArrayList<>());
        public final List<String> infos = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onFlag(Check check, CheckSettings settings, PlayerData data, double vl, String info, long nowNanos) {
            flags.add(check.id());
            infos.add(info);
        }

        @Override
        public void onPunish(Check check, CheckSettings settings, PlayerData data, double vl, String info, long nowNanos) {
            punishments.add(check.id());
        }
    }

    public final Recorder recorder = new Recorder();
    public final ViolationManager violations = new ViolationManager(recorder);
    public final CheckManager checks;
    public final ServerProtocol protocol;
    public final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

    public TestHarness(int protocolVersion) {
        this(protocolVersion, null);
    }

    public TestHarness(int protocolVersion, ModernItemReader modernReader) {
        this.protocol = ServerProtocol.of(protocolVersion);
        this.checks = new CheckManager(new ItemAnalyzer(modernReader), (DecodingGuard.CreativeRewriter) null, violations);
        checks.applyItemLimits(new ItemLimits());
    }

    public PlayerData newPlayer() {
        return new PlayerData(new FakeHandle(), checks.all().size(), 32);
    }

    public PlayerData creativePlayer() {
        PlayerData data = newPlayer();
        data.creative(true, 0L);
        return data;
    }

    /** Runs the whole chain, and returns the context with the outcome. */
    public PacketContext run(PlayerData data, PacketKind kind, byte[] body, long nowNanos) {
        ByteBuf buffer = Unpooled.wrappedBuffer(body);
        return run(data, kind, buffer, nowNanos);
    }

    public PacketContext run(PlayerData data, PacketKind kind, ByteBuf buffer, long nowNanos) {
        PacketContext context = data.context().prepare(kind, buffer, buffer.readerIndex(), buffer.writerIndex(),
                buffer.readableBytes() + 1, nowNanos, protocol, data, null);
        PacketPipeline.run(context, checks.chain(kind), checks.malformed, (check, error) -> errors.add(error));
        return context;
    }

    public PacketContext run(PlayerData data, PacketKind kind, Wire body) {
        return run(data, kind, body.toBytes(), System.nanoTime());
    }

    public boolean flagged(String checkId) {
        return recorder.flags.contains(checkId);
    }

    public Check check(String id) {
        return checks.byId(id);
    }
}
