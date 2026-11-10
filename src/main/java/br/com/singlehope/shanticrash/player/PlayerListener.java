package br.com.singlehope.shanticrash.player;

import br.com.singlehope.shanticrash.alerts.AlertManager;
import br.com.singlehope.shanticrash.compatibility.CompatibilityManager;
import br.com.singlehope.shanticrash.configuration.ConfigurationManager;
import br.com.singlehope.shanticrash.configuration.PluginSettings;
import br.com.singlehope.shanticrash.scheduler.SchedulerManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Copies from Bukkit what Netty threads need to know about a player, on the thread that is allowed to
 * read it: permissions and game mode become plain fields, so packet handlers never touch the entity.
 */
public final class PlayerListener implements Listener {
    private final PlayerDataManager players;
    private final AlertManager alerts;
    private final ConfigurationManager configuration;
    private final CompatibilityManager compatibility;
    private final SchedulerManager scheduler;

    public PlayerListener(PlayerDataManager players, AlertManager alerts, ConfigurationManager configuration,
                          CompatibilityManager compatibility, SchedulerManager scheduler) {
        this.players = players;
        this.alerts = alerts;
        this.configuration = configuration;
        this.compatibility = compatibility;
        this.scheduler = scheduler;
    }

    private PlayerData dataOf(Player player) {
        PlayerData data = players.byUuid(player.getUniqueId());
        if (data != null) {
            return data;
        }
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) {
            return null;
        }
        data = players.get(user);
        if (data != null) {
            players.bindUuid(player.getUniqueId(), data);
        }
        return data;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        synchronize(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerData data = players.byUuid(event.getPlayer().getUniqueId());
        if (data != null) {
            alerts.removeRecipient(data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        PlayerData data = dataOf(event.getPlayer());
        if (data != null) {
            data.creative(event.getNewGameMode() == GameMode.CREATIVE, System.nanoTime());
        }
    }

    /** Must run on the thread that owns the player. */
    private void synchronize(Player player, boolean joining) {
        PlayerData data = dataOf(player);
        if (data == null) {
            return;
        }
        PluginSettings settings = configuration.settings();
        data.bypass(settings.bypassEnabled && player.hasPermission("shanticrash.bypass"));
        data.creative(player.getGameMode() == GameMode.CREATIVE, 0L);

        boolean bedrock = (settings.geyserSupport || settings.floodgateSupport)
                && compatibility.isBedrock(player.getUniqueId());
        data.bedrock(bedrock);
        double multiplier = 1.0D;
        if (bedrock) {
            multiplier = settings.bedrockFloodMultiplier;
        } else if (settings.viaSupport && compatibility.hasViaVersion()
                && data.handle().clientProtocol() > 0 && data.handle().clientProtocol() < settings.viaOldClientProtocol) {
            multiplier = settings.viaOldClientFloodMultiplier;
        }
        data.floodMultiplier(multiplier);

        boolean permitted = player.hasPermission("shanticrash.alerts") || player.hasPermission("shanticrash.admin");
        data.alertsPermitted(permitted);
        data.printerAlertsPermitted(player.hasPermission("shanticrash.alerts.printer"));
        if (joining) {
            data.joined(true);
            data.alertsEnabled(permitted && settings.alertsOnJoin);
        } else if (!permitted) {
            data.alertsEnabled(false);
        }
        if (permitted || data.isPrinterAlertsPermitted()) {
            alerts.addRecipient(data);
        } else {
            alerts.removeRecipient(data);
        }
    }

    /** Refreshes everyone after a reload or a late start, each on their own thread. */
    public void synchronizeOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.entity(player, () -> synchronize(player, !isKnown(player)), () -> { });
        }
    }

    private boolean isKnown(Player player) {
        PlayerData data = players.byUuid(player.getUniqueId());
        return data != null && data.isJoined();
    }
}
