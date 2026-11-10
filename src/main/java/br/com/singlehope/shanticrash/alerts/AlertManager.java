package br.com.singlehope.shanticrash.alerts;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.configuration.ConfigurationManager;
import br.com.singlehope.shanticrash.configuration.Messages;
import br.com.singlehope.shanticrash.configuration.PluginSettings;
import br.com.singlehope.shanticrash.logging.EventLogger;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.violation.CheckState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Formats and delivers alerts. Every alert goes straight to the channel of each recipient, so sending
 * one does not schedule anything on the server.
 *
 * <p>An attacker controls how often violations happen, so alerts are limited in two ways: per player
 * per check, and overall per second. Suppressed alerts are counted and reported when the rate drops.
 */
public final class AlertManager {
    private final ConfigurationManager configuration;
    private final Messages messages;
    private final Logger console;
    private final List<PlayerData> recipients = new CopyOnWriteArrayList<>();
    private final AtomicLong currentSecond = new AtomicLong();
    private final AtomicInteger sentThisSecond = new AtomicInteger();
    private final AtomicLong suppressed = new AtomicLong();
    private volatile EventLogger logger;

    public AlertManager(ConfigurationManager configuration, Messages messages, Logger console) {
        this.configuration = configuration;
        this.messages = messages;
        this.console = console;
    }

    public void logger(EventLogger logger) {
        this.logger = logger;
    }

    public EventLogger logger() {
        return logger;
    }

    public void addRecipient(PlayerData data) {
        if (!recipients.contains(data)) {
            recipients.add(data);
        }
    }

    public void removeRecipient(PlayerData data) {
        recipients.remove(data);
    }

    public void onFlag(Check check, CheckSettings settings, PlayerData data, double vl, String info, long nowNanos) {
        PluginSettings config = configuration.settings();
        CheckState state = data.state(check.index());
        if (!state.acquireAlert(config.alertCooldownNanos, nowNanos)) {
            return;
        }
        String player = data.handle().name();
        EventLogger log = logger;
        if (log != null && config.alertsStore) {
            log.log(player + " (" + data.handle().clientVersion() + ") " + check.id()
                    + " vl=" + format(vl) + "/" + format(settings.punishAt) + " " + info);
        }
        if (!config.alertsEnabled || config.disabledAlertCategories.contains(check.category())) {
            return;
        }
        if (!acquireGlobal(config.maxAlertsPerSecond)) {
            suppressed.incrementAndGet();
            return;
        }
        send(build(check, settings, player, vl, info), check.category(), config);
    }

    private Component build(Check check, CheckSettings settings, String player, double vl, String info) {
        String[] placeholders = {
                "player", player,
                "check", check.category().toUpperCase(Locale.ROOT),
                "type", check.type(),
                "vl", format(vl),
                "max-vl", format(settings.punishAt),
                "description", check.description(),
                "infos", info
        };
        Component hover = Component.empty();
        boolean first = true;
        for (String line : messages.rawList("alerts.hover")) {
            if (!first) {
                hover = hover.append(Component.newline());
            }
            hover = hover.append(Messages.parse(messages.apply(line, placeholders)));
            first = false;
        }
        return messages.component("alerts.format", placeholders).hoverEvent(HoverEvent.showText(hover));
    }

    private void send(Component message, String category, PluginSettings config) {
        for (PlayerData recipient : recipients) {
            if (recipient.isAlertsEnabled() && !recipient.mutedCategories().contains(category)) {
                try {
                    recipient.handle().sendMessage(message);
                } catch (RuntimeException ignored) {
                    // The recipient disconnected between the check and the write.
                }
            }
        }
        if (config.alertsConsole) {
            console.info(Messages.plain(message));
        }
    }

    /** Administrative notice with no associated check, delivered under the same rate limit. */
    public void notice(Component message) {
        PluginSettings config = configuration.settings();
        if (config.alertsEnabled && acquireGlobal(config.maxAlertsPerSecond)) {
            send(message, "", config);
        }
    }

    /** Printer notifications use their own permission. */
    public void printerNotice(Component message) {
        PluginSettings config = configuration.settings();
        if (!config.alertsEnabled || !acquireGlobal(config.maxAlertsPerSecond)) {
            return;
        }
        for (PlayerData recipient : recipients) {
            if (recipient.isPrinterAlertsPermitted()) {
                recipient.handle().sendMessage(message);
            }
        }
    }

    private boolean acquireGlobal(int maxPerSecond) {
        long second = System.nanoTime() / 1_000_000_000L;
        long known = currentSecond.get();
        if (second != known && currentSecond.compareAndSet(known, second)) {
            sentThisSecond.set(0);
            long dropped = suppressed.getAndSet(0L);
            if (dropped > 0L) {
                console.warning(dropped + " alertas suprimidos pelo limite por segundo.");
            }
        }
        return sentThisSecond.incrementAndGet() <= maxPerSecond;
    }

    public static String format(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
