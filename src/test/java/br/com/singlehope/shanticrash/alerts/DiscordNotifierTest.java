package br.com.singlehope.shanticrash.alerts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordNotifierTest {
    private static final String URL = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyzABCDEF_-0123";
    private static final long S = 1_000_000_000L;

    private static final class Recording implements DiscordNotifier.Transport {
        final List<String> bodies = new ArrayList<>();
        int status = 204;
        boolean fail;

        @Override
        public DiscordNotifier.Response post(String json) throws IOException {
            if (fail) {
                throw new IOException("sem rede");
            }
            bodies.add(json);
            return new DiscordNotifier.Response(status, status == 429 ? 5_000L : 0L);
        }
    }

    private static DiscordNotifier notifier(Recording transport, int perMinute, long groupMillis, int capacity) {
        return new DiscordNotifier(new DiscordNotifier.Settings(true, URL, 1000, perMinute, groupMillis, capacity, null),
                transport, null);
    }

    @Test
    void onlyDiscordWebhooksAreAccepted() {
        assertTrue(DiscordNotifier.validUrl(URL));
        assertTrue(DiscordNotifier.validUrl(URL.replace("discord.com", "canary.discord.com")));
        assertFalse(DiscordNotifier.validUrl(""));
        assertFalse(DiscordNotifier.validUrl(null));
        assertFalse(DiscordNotifier.validUrl(URL.replace("https", "http")));
        assertFalse(DiscordNotifier.validUrl("https://evil.example/api/webhooks/1234567/abcdefghijklmnopqrstuvwxyz"));
        assertFalse(DiscordNotifier.validUrl(URL + "?wait=true"));
        assertFalse(new DiscordNotifier.Settings(true, "https://example.com", 1000, 5, 0, 10, null).enabled);
    }

    @Test
    void repeatedEventsAreGroupedIntoOneMessage() {
        Recording transport = new Recording();
        DiscordNotifier notifier = notifier(transport, 10, 10_000L, 10);
        for (int i = 0; i < 5; i++) {
            notifier.report("Jogador", "flood.packet-limits", "flood", i, "KICK", 765, i * S);
        }
        assertEquals(0, notifier.flush(5 * S));
        assertEquals(1, notifier.flush(11 * S));
        assertEquals(1, transport.bodies.size());
        assertTrue(transport.bodies.get(0).contains("\"Ocorr\\u00eancias\",\"value\":\"5\""), transport.bodies.get(0));
    }

    @Test
    void rateLimitAndQueueCapacityAreRespected() {
        Recording transport = new Recording();
        DiscordNotifier notifier = notifier(transport, 2, 0L, 3);
        for (int i = 0; i < 5; i++) {
            notifier.report("j" + i, "check", "x", 1, "KICK", 765, 0L);
        }
        assertEquals(2L, notifier.dropped());
        assertEquals(2, notifier.flush(0L));
        assertEquals(1, notifier.queued());
        assertEquals(0, notifier.flush(S));
        assertEquals(1, notifier.flush(31 * S));
    }

    @Test
    void failuresAndThrottlingNeverThrow() {
        Recording transport = new Recording();
        DiscordNotifier notifier = notifier(transport, 10, 0L, 10);
        transport.fail = true;
        notifier.report("a", "c", "x", 1, "KICK", 765, 0L);
        assertEquals(0, notifier.flush(0L));
        assertEquals(1L, notifier.failed());
        transport.fail = false;
        transport.status = 429;
        notifier.report("b", "c", "x", 1, "KICK", 765, 0L);
        notifier.report("c", "c", "x", 1, "KICK", 765, 0L);
        assertEquals(0, notifier.flush(0L));
        assertEquals(2, notifier.queued());
        assertEquals(0, notifier.flush(2 * S));
        transport.status = 204;
        assertEquals(2, notifier.flush(6 * S));
        assertEquals(0, notifier.queued());
    }

    @Test
    void textIsEscapedAndMentionsAreDisabled() {
        Recording transport = new Recording();
        DiscordNotifier notifier = notifier(transport, 10, 0L, 10);
        notifier.report("@everyone \"x\"\n**y**", "c\\d", "x", 1, "KICK", 765, 0L);
        notifier.flush(0L);
        String body = transport.bodies.get(0);
        assertTrue(body.contains("\"allowed_mentions\":{\"parse\":[]}"));
        assertTrue(body.contains("\\\\@everyone \\\"x\\\" \\\\*\\\\*y\\\\*\\\\*"), body);
        assertTrue(body.contains("c\\\\d"), body);
        assertFalse(body.contains("\n"));
    }
}
