package br.com.singlehope.shanticrash.alerts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class DiscordNotifier {
    private static final Pattern WEBHOOK = Pattern.compile(
            "^https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/webhooks/\\d{5,25}/[A-Za-z0-9_\\-]{20,128}$");

    public interface Transport {
        Response post(String json) throws IOException;
    }

    public static final class Response {
        public final int status;
        public final long retryAfterMillis;

        public Response(int status, long retryAfterMillis) {
            this.status = status;
            this.retryAfterMillis = retryAfterMillis;
        }
    }

    public static final class Settings {
        public final boolean enabled;
        public final String url;
        public final int timeoutMillis;
        public final int maxPerMinute;
        public final long groupNanos;
        public final int queueCapacity;
        public final String username;

        public Settings(boolean enabled, String url, int timeoutMillis, int maxPerMinute, long groupMillis,
                        int queueCapacity, String username) {
            this.enabled = enabled && validUrl(url);
            this.url = url == null ? "" : url.trim();
            this.timeoutMillis = Math.max(500, Math.min(timeoutMillis, 30_000));
            this.maxPerMinute = Math.max(1, Math.min(maxPerMinute, 30));
            this.groupNanos = Math.max(0L, Math.min(groupMillis, 300_000L)) * 1_000_000L;
            this.queueCapacity = Math.max(1, Math.min(queueCapacity, 1024));
            this.username = username == null || username.isEmpty() ? "ShAntiCrash" : username;
        }
    }

    static final class Pending {
        final String player;
        final String check;
        final String packet;
        final String action;
        final int protocol;
        final long firstNanos;
        final long timeMillis;
        double maxVl;
        int count;

        Pending(String player, String check, String packet, String action, int protocol, double vl, long nowNanos,
                long timeMillis) {
            this.player = player;
            this.check = check;
            this.packet = packet;
            this.action = action;
            this.protocol = protocol;
            this.maxVl = vl;
            this.firstNanos = nowNanos;
            this.timeMillis = timeMillis;
            this.count = 1;
        }
    }

    private final Settings settings;
    private final Transport transport;
    private final Logger logger;
    private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Thread worker;
    private volatile boolean running;
    private double tokens;
    private long lastRefill;
    private long blockedUntil;
    private long lastWarning;

    public DiscordNotifier(Settings settings, Transport transport, Logger logger) {
        this.settings = settings;
        this.transport = transport == null ? new HttpTransport(settings) : transport;
        this.logger = logger;
        this.tokens = settings.maxPerMinute;
        this.worker = new Thread(this::loop, "ShAntiCrash-Discord");
        this.worker.setDaemon(true);
    }

    public static boolean validUrl(String url) {
        return url != null && WEBHOOK.matcher(url.trim()).matches();
    }

    public boolean enabled() {
        return settings.enabled;
    }

    public void start() {
        if (settings.enabled && !running) {
            running = true;
            lastRefill = System.nanoTime();
            worker.start();
        }
    }

    public void close() {
        running = false;
        worker.interrupt();
    }

    public void report(String player, String check, String packet, double vl, String action, int protocol, long nowNanos) {
        if (!settings.enabled) {
            return;
        }
        String key = player + '\u0000' + check + '\u0000' + action;
        synchronized (pending) {
            Pending existing = pending.get(key);
            if (existing != null) {
                existing.count++;
                if (vl > existing.maxVl) {
                    existing.maxVl = vl;
                }
                return;
            }
            if (pending.size() >= settings.queueCapacity) {
                dropped.incrementAndGet();
                return;
            }
            pending.put(key, new Pending(player, check, packet, action, protocol, vl, nowNanos, System.currentTimeMillis()));
        }
    }

    public int flush(long nowNanos) {
        refill(nowNanos);
        if (nowNanos - blockedUntil < 0L) {
            return 0;
        }
        List<Pending> due = new ArrayList<>();
        synchronized (pending) {
            Iterator<Pending> iterator = pending.values().iterator();
            while (iterator.hasNext() && tokens >= 1.0D) {
                Pending entry = iterator.next();
                if (nowNanos - entry.firstNanos < settings.groupNanos) {
                    continue;
                }
                iterator.remove();
                tokens -= 1.0D;
                due.add(entry);
            }
        }
        int delivered = 0;
        for (int i = 0; i < due.size(); i++) {
            Pending entry = due.get(i);
            try {
                Response response = transport.post(json(entry, settings.username));
                if (response.status == 429) {
                    blockedUntil = nowNanos + Math.max(1_000L, Math.min(response.retryAfterMillis, 60_000L)) * 1_000_000L;
                    failed.incrementAndGet();
                    warn("Discord limitou o envio de alertas; tentando de novo mais tarde.", nowNanos);
                    requeue(due.subList(i, due.size()));
                    break;
                }
                if (response.status >= 200 && response.status < 300) {
                    sent.incrementAndGet();
                    delivered++;
                } else {
                    failed.incrementAndGet();
                    warn("Webhook do Discord respondeu HTTP " + response.status + ".", nowNanos);
                }
            } catch (IOException | RuntimeException e) {
                failed.incrementAndGet();
                warn("Falha ao enviar alerta ao Discord: " + e.getClass().getSimpleName(), nowNanos);
            }
        }
        return delivered;
    }

    private void requeue(List<Pending> entries) {
        synchronized (pending) {
            for (Pending entry : entries) {
                String key = entry.player + '\u0000' + entry.check + '\u0000' + entry.action;
                Pending newer = pending.get(key);
                if (newer != null) {
                    newer.count += entry.count;
                    newer.maxVl = Math.max(newer.maxVl, entry.maxVl);
                } else if (pending.size() < settings.queueCapacity) {
                    pending.put(key, entry);
                } else {
                    dropped.incrementAndGet();
                }
                tokens = Math.min(settings.maxPerMinute, tokens + 1.0D);
            }
        }
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefill;
        if (elapsed <= 0L) {
            return;
        }
        tokens = Math.min(settings.maxPerMinute, tokens + settings.maxPerMinute * (elapsed / 60_000_000_000.0D));
        lastRefill = nowNanos;
    }

    private void warn(String message, long nowNanos) {
        if (logger != null && (lastWarning == 0L || nowNanos - lastWarning > 60_000_000_000L)) {
            lastWarning = nowNanos;
            logger.warning(message);
        }
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                if (!running) {
                    return;
                }
            }
            flush(System.nanoTime());
        }
    }

    public long sent() {
        return sent.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public long failed() {
        return failed.get();
    }

    public int queued() {
        synchronized (pending) {
            return pending.size();
        }
    }

    static String json(Pending entry, String username) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder fields = new StringBuilder();
        field(fields, "Jogador", entry.player);
        field(fields, "Check", entry.check);
        field(fields, "Pacote", entry.packet == null ? "-" : entry.packet);
        field(fields, "VL", Math.round(entry.maxVl * 100.0D) / 100.0D + "");
        field(fields, "Ação", entry.action);
        field(fields, "Protocolo", Integer.toString(entry.protocol));
        field(fields, "Ocorrências", Integer.toString(entry.count));
        return "{\"username\":\"" + escape(username) + "\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{"
                + "\"title\":\"" + escape("ShAntiCrash: " + entry.check) + "\","
                + "\"color\":" + ("KICK".equals(entry.action) || "DROP".equals(entry.action) ? 15158332 : 15105570) + ","
                + "\"timestamp\":\"" + format.format(new Date(entry.timeMillis)) + "\","
                + "\"fields\":[" + fields + "]}]}";
    }

    private static void field(StringBuilder fields, String name, String value) {
        if (fields.length() > 0) {
            fields.append(',');
        }
        fields.append("{\"name\":\"").append(escape(name)).append("\",\"value\":\"")
                .append(escape(value.isEmpty() ? "-" : value)).append("\",\"inline\":true}");
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        int length = Math.min(value.length(), 256);
        StringBuilder out = new StringBuilder(length + 16);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                case '\r':
                case '\t':
                    out.append(' ');
                    break;
                case '*':
                case '_':
                case '~':
                case '`':
                case '|':
                case '>':
                case '@':
                    out.append('\\').append('\\').append(c);
                    break;
                default:
                    if (c < 0x20 || c == 0x7F) {
                        out.append(' ');
                    } else if (c > 0x7E) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private static final class HttpTransport implements Transport {
        private final Settings settings;

        HttpTransport(Settings settings) {
            this.settings = settings;
        }

        @Override
        public Response post(String json) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(settings.url).openConnection();
            try {
                connection.setConnectTimeout(settings.timeoutMillis);
                connection.setReadTimeout(settings.timeoutMillis);
                connection.setRequestMethod("POST");
                connection.setInstanceFollowRedirects(false);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("User-Agent", "ShAntiCrash");
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
                int status = connection.getResponseCode();
                long retry = 0L;
                String header = connection.getHeaderField("Retry-After");
                if (header != null) {
                    try {
                        retry = (long) (Double.parseDouble(header.trim()) * 1000.0D);
                    } catch (NumberFormatException ignored) {
                        retry = 5_000L;
                    }
                }
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream != null) {
                    byte[] sink = new byte[512];
                    int total = 0;
                    while (total < 8192 && stream.read(sink) != -1) {
                        total += sink.length;
                    }
                    stream.close();
                }
                return new Response(status, retry);
            } finally {
                connection.disconnect();
            }
        }
    }

    Map<String, Pending> pendingView() {
        return pending;
    }
}
