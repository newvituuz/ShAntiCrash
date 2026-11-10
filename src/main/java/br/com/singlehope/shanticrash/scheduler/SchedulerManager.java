package br.com.singlehope.shanticrash.scheduler;

import io.github.retrooper.packetevents.util.folia.FoliaScheduler;
import io.github.retrooper.packetevents.util.folia.TaskWrapper;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The only place that schedules work. On Folia each method maps to the scheduler that owns the
 * target; on other servers they all fall back to the Bukkit scheduler, which is what PacketEvents'
 * schedulers do underneath.
 *
 * <p>Nothing here waits for a result, so no region can block on another one.
 */
public final class SchedulerManager {
    private final Plugin plugin;
    private final List<TaskWrapper> repeating = new CopyOnWriteArrayList<>();

    public SchedulerManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isFolia() {
        return FoliaScheduler.isFolia();
    }

    /** Work that belongs to the server as a whole, such as running console commands. */
    public void global(Runnable task) {
        FoliaScheduler.getGlobalRegionScheduler().execute(plugin, task);
    }

    /**
     * Work on a player. The task follows the player between regions, and {@code retired} runs instead
     * if the player disconnects first, so nothing stays attached to a player who left.
     */
    public void entity(Player player, Runnable task, Runnable retired) {
        FoliaScheduler.getEntityScheduler().execute(player, plugin, task, retired, 1L);
    }

    /** Work that reads or changes a location, on the thread of the region that owns it. */
    public void region(Location location, Runnable task) {
        FoliaScheduler.getRegionScheduler().execute(plugin, location, task);
    }

    /** Work with no relation to the game state, off the main threads. */
    public void async(Runnable task) {
        FoliaScheduler.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    /** Repeating background task, cancelled by {@link #shutdown}. */
    public void asyncRepeating(Runnable task, long delay, long period, TimeUnit unit) {
        repeating.add(FoliaScheduler.getAsyncScheduler().runAtFixedRate(plugin, ignored -> task.run(), delay, period, unit));
    }

    public void shutdown() {
        for (TaskWrapper task : repeating) {
            task.cancel();
        }
        repeating.clear();
        FoliaScheduler.getAsyncScheduler().cancel(plugin);
        FoliaScheduler.getGlobalRegionScheduler().cancel(plugin);
    }
}
