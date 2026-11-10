package br.com.singlehope.shanticrash.punishment;

import br.com.singlehope.shanticrash.alerts.AlertManager;
import br.com.singlehope.shanticrash.alerts.DiscordNotifier;
import br.com.singlehope.shanticrash.api.event.EventDispatcher;
import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.configuration.ConfigurationManager;
import br.com.singlehope.shanticrash.configuration.Messages;
import br.com.singlehope.shanticrash.configuration.PluginSettings;
import br.com.singlehope.shanticrash.metrics.PacketMetrics;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.scheduler.SchedulerManager;
import br.com.singlehope.shanticrash.violation.ViolationListener;
import org.bukkit.Bukkit;

import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes the consequences of reaching a threshold.
 *
 * <p>Disconnecting goes through the channel, which is safe on the Netty thread and works even while the
 * server is frozen. Console commands are queued and drained by the global scheduler, in a bounded queue
 * and with one drain task armed at a time, so an attack can not pile up work on the main thread.
 */
public final class PunishmentManager implements ViolationListener {
    private static final int COMMANDS_PER_DRAIN = 32;

    private final ConfigurationManager configuration;
    private final Messages messages;
    private final AlertManager alerts;
    private final SchedulerManager scheduler;
    private final PacketMetrics metrics;
    private final Logger logger;

    private final Queue<String> commandQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean drainArmed = new AtomicBoolean();
    private volatile EventDispatcher events;
    private volatile DiscordNotifier discord;

    public PunishmentManager(ConfigurationManager configuration, Messages messages, AlertManager alerts,
                             SchedulerManager scheduler, PacketMetrics metrics, Logger logger) {
        this.configuration = configuration;
        this.messages = messages;
        this.alerts = alerts;
        this.scheduler = scheduler;
        this.metrics = metrics;
        this.logger = logger;
    }

    public void events(EventDispatcher events) {
        this.events = events;
    }

    public void discord(DiscordNotifier discord) {
        this.discord = discord;
    }

    @Override
    public void onFlag(Check check, CheckSettings settings, PlayerData data, double vl, String info, long nowNanos) {
        metrics.violation();
        alerts.onFlag(check, settings, data, vl, info, nowNanos);
        EventDispatcher dispatcher = events;
        if (dispatcher != null) {
            dispatcher.violation(check, data, vl, info);
        }
        DiscordNotifier notifier = discord;
        if (notifier != null && configuration.settings().discordIncludeFlags) {
            notifier.report(data.handle().name(), check.id(), check.category(), vl, "FLAG",
                    data.handle().clientProtocol(), nowNanos);
        }
    }

    @Override
    public void onPunish(Check check, CheckSettings settings, PlayerData data, double vl, String info, long nowNanos) {
        PluginSettings config = configuration.settings();
        if (!config.punishmentsEnabled) {
            return;
        }
        if (data.lastPunishNanos != 0L && nowNanos - data.lastPunishNanos < config.punishmentCooldownNanos) {
            return;
        }
        EventDispatcher dispatcher = events;
        if (dispatcher != null && !dispatcher.punishment(check, data, settings.actions, vl)) {
            return;
        }
        data.lastPunishNanos = nowNanos;
        DiscordNotifier notifier = discord;
        if (notifier != null && !settings.actions.isEmpty() && !settings.actions.contains(PunishmentAction.NONE)) {
            notifier.report(data.handle().name(), check.id(), check.category(), vl, primary(settings.actions),
                    data.handle().clientProtocol(), nowNanos);
        }

        if (settings.actions.contains(PunishmentAction.COMMAND)) {
            enqueueCommands(check, settings, data, vl, info, config);
        }
        if (settings.actions.contains(PunishmentAction.DROP)) {
            disconnect(check, data, false, settings, vl, info);
        } else if (settings.actions.contains(PunishmentAction.KICK)) {
            disconnect(check, data, true, settings, vl, info);
        }
    }

    private static String primary(java.util.Set<PunishmentAction> actions) {
        if (actions.contains(PunishmentAction.DROP)) {
            return "DROP";
        }
        if (actions.contains(PunishmentAction.KICK)) {
            return "KICK";
        }
        if (actions.contains(PunishmentAction.COMMAND)) {
            return "COMMAND";
        }
        return actions.iterator().next().name();
    }

    private void disconnect(Check check, PlayerData data, boolean withMessage, CheckSettings settings,
                            double vl, String info) {
        data.closing(true);
        String player = data.handle().name();
        if (alerts.logger() != null) {
            alerts.logger().log(player + " desconectado por " + check.id() + " vl=" + AlertManager.format(vl) + " " + info);
        }
        alerts.notice(messages.component("alerts.kicked",
                "player", player,
                "check", check.category().toUpperCase(Locale.ROOT),
                "type", check.type()));
        if (withMessage) {
            String reason = settings.kickMessage.isEmpty() ? messages.raw("kick") : settings.kickMessage;
            data.handle().kick(messages.apply(reason));
        } else {
            data.handle().close();
        }
    }

    private void enqueueCommands(Check check, CheckSettings settings, PlayerData data, double vl, String info,
                                 PluginSettings config) {
        for (String template : settings.commands) {
            if (queued.get() >= config.maxQueuedCommands) {
                logger.warning("Fila de comandos de punição cheia, descartando comando de " + check.id());
                return;
            }
            String command = template
                    .replace("%player%", data.handle().name())
                    .replace("%check%", check.category().toUpperCase(Locale.ROOT))
                    .replace("%type%", check.type())
                    .replace("%vl%", AlertManager.format(vl))
                    .replace("%max-vl%", AlertManager.format(settings.punishAt))
                    .replace("%description%", check.description())
                    .replace("%infos%", commandSafe(info));
            commandQueue.add(command);
            queued.incrementAndGet();
        }
        armDrain();
    }

    /**
     * The info text can hold values from a packet, and it ends up in a console command. Only characters
     * with no meaning to a command line stay, so a client can not smuggle arguments into it.
     */
    static String commandSafe(String value) {
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 128));
        for (int i = 0; i < value.length() && safe.length() < 128; i++) {
            char c = value.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == ' ' || c == '.' || c == '-' || c == '=' || c == '_' ? c : '?');
        }
        return safe.toString();
    }

    private void armDrain() {
        if (drainArmed.compareAndSet(false, true)) {
            scheduler.global(this::drain);
        }
    }

    private void drain() {
        drainArmed.set(false);
        for (int i = 0; i < COMMANDS_PER_DRAIN; i++) {
            String command = commandQueue.poll();
            if (command == null) {
                return;
            }
            queued.decrementAndGet();
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Falha ao executar comando de punição: " + command, e);
            }
        }
        if (!commandQueue.isEmpty()) {
            armDrain();
        }
    }
}
