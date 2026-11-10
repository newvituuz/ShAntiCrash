package br.com.singlehope.shanticrash.packet.decoder;

import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketPipeline;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.support.TestHarness;
import br.com.singlehope.shanticrash.support.TestPacketEvents;
import br.com.singlehope.shanticrash.support.Wire;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernItemFuzzTest {
    private static final ServerVersion[] VERSIONS = {
            ServerVersion.V_1_20_5, ServerVersion.V_1_21_4, ServerVersion.V_1_21_11, ServerVersion.V_26_3};

    private static byte[] randomItem(Random random, int componentTypes, boolean prefixed) {
        Wire item = Wire.create().s(36).v(1 + random.nextInt(64)).v(1 + random.nextInt(800));
        int present = random.nextInt(5);
        int absent = random.nextInt(3);
        item.v(present).v(absent);
        for (int i = 0; i < present; i++) {
            item.v(random.nextInt(componentTypes + 2));
            byte[] value = new byte[random.nextInt(random.nextBoolean() ? 16 : 600)];
            random.nextBytes(value);
            if (prefixed) {
                item.prefixed(value);
            } else {
                item.raw(value);
            }
        }
        for (int i = 0; i < absent; i++) {
            item.v(random.nextInt(componentTypes + 2));
        }
        return item.toBytes();
    }

    @Test
    void randomComponentsNeverCauseInternalErrors() {
        assertTimeoutPreemptively(ofSeconds(120), () -> {
            Random random = new Random(42L);
            for (ServerVersion version : VERSIONS) {
                TestPacketEvents.install(version);
                PacketEventsItemReader.warmUp(version.toClientVersion());
                TestHarness harness = new TestHarness(version.getProtocolVersion(), new PacketEventsItemReader());
                int componentTypes = ComponentTypes.getRegistry().getEntries().size();
                int packetId = PacketType.Play.Client.CREATIVE_INVENTORY_ACTION.getId(version.toClientVersion());
                boolean prefixed = harness.protocol.v1_21_5;
                for (int i = 0; i < 3000; i++) {
                    PlayerData data = harness.creativePlayer();
                    ByteBuf frame = Unpooled.wrappedBuffer(Wire.create().v(packetId)
                            .raw(randomItem(random, componentTypes, prefixed)).toBytes());
                    PacketReceiveEvent event = TestPacketEvents.event(frame, version);
                    PacketContext context = data.context().prepare(PacketKind.CREATIVE_SLOT, frame, frame.readerIndex(),
                            frame.writerIndex(), frame.readableBytes() + 1, 1_000L + i, harness.protocol, data, event);
                    PacketPipeline.run(context, harness.checks.chain(PacketKind.CREATIVE_SLOT), harness.checks.malformed,
                            (check, error) -> harness.errors.add(error));
                }
                assertTrue(harness.errors.isEmpty(), version + ": " + harness.errors);
            }
        });
    }
}
