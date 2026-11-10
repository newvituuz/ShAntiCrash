package br.com.singlehope.shanticrash.mechanics;

import br.com.singlehope.shanticrash.configuration.ConfigurationManager;
import br.com.singlehope.shanticrash.configuration.PluginSettings;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional gameplay protections. They are independent from the packet checks, change vanilla behaviour
 * and therefore are all off by default. They run in Bukkit events, on the thread owning the entity or
 * block, so they only touch objects from the event itself.
 */
public final class MechanicsListener implements Listener {
    private static final int MAX_TRACKED_CHUNKS = 4096;

    private final ConfigurationManager configuration;
    private final Map<UUID, Long> lastPortal = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastShear = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastContainer = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> redstoneCounters = new ConcurrentHashMap<>();
    private volatile long redstoneWindow;

    public MechanicsListener(ConfigurationManager configuration) {
        this.configuration = configuration;
    }

    private static boolean cooldownActive(Map<UUID, Long> times, UUID id, long cooldownMillis) {
        long now = System.nanoTime();
        Long last = times.get(id);
        if (last != null && now - last < cooldownMillis * 1_000_000L) {
            return true;
        }
        times.put(id, now);
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastPortal.remove(id);
        lastShear.remove(id);
        lastContainer.remove(id);
    }

    /** Reusing a portal at high speed forces chunk loading in two worlds for each trip. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        long delay = configuration.settings().netherPortalDelayMillis;
        if (delay > 0L && cooldownActive(lastPortal, event.getPlayer().getUniqueId(), delay)) {
            event.setCancelled(true);
        }
    }

    /** Arrows far above the fastest vanilla shot are the result of a client side bow exploit. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        double max = configuration.settings().maxArrowVelocity;
        Projectile projectile = event.getEntity();
        if (max <= 0.0D || !(projectile instanceof Arrow)) {
            return;
        }
        Vector velocity = projectile.getVelocity();
        double speed = velocity.length();
        if (speed > max) {
            projectile.setVelocity(velocity.multiply(max / speed));
        }
    }

    /** Macro shearing is used to empty farms and to produce drops faster than they can be handled. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        long cooldown = configuration.settings().shearsCooldownMillis;
        if (cooldown > 0L && cooldownActive(lastShear, event.getPlayer().getUniqueId(), cooldown)) {
            event.setCancelled(true);
        }
    }

    /** Closes a window opened while breaking, which prevents item duplication through it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (configuration.settings().breakClosesInventory) {
            event.getPlayer().closeInventory();
        }
    }

    /** Opening containers at high speed triggers neighbouring block updates and item transfers. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        long delay = configuration.settings().containerOpenDelayMillis;
        if (delay <= 0L || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !isContainer(block.getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (cooldownActive(lastContainer, player.getUniqueId(), delay)) {
            event.setCancelled(true);
        }
    }

    private static boolean isContainer(Material material) {
        String name = material.name().toUpperCase(Locale.ROOT);
        return name.contains("CHEST") || name.contains("BARREL") || name.contains("SHULKER")
                || name.contains("HOPPER") || name.contains("DISPENSER") || name.contains("DROPPER");
    }

    /**
     * Generic throttle for redstone updates in rails, trapdoors and comparators, per chunk. Loops of
     * these blocks update neighbours in cascade, and a machine built to overload a region does so
     * through sheer volume. Above the limit, the block just keeps its current state until the next
     * second. It is a coarse limit on volume, not a fix for any specific exploit.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRedstone(BlockRedstoneEvent event) {
        PluginSettings settings = configuration.settings();
        if (!settings.redstoneThrottleEnabled) {
            return;
        }
        Block block = event.getBlock();
        String type = block.getType().name();
        if (!type.contains("RAIL") && !type.contains("TRAP_DOOR") && !type.contains("TRAPDOOR") && !type.contains("COMPARATOR")) {
            return;
        }
        long second = System.nanoTime() / 1_000_000_000L;
        if (second != redstoneWindow) {
            redstoneWindow = second;
            redstoneCounters.clear();
        }
        if (redstoneCounters.size() >= MAX_TRACKED_CHUNKS) {
            return;
        }
        Chunk chunk = block.getChunk();
        long key = ((long) chunk.getX() << 32) ^ (chunk.getZ() & 0xFFFFFFFFL);
        AtomicInteger counter = redstoneCounters.computeIfAbsent(key, ignored -> new AtomicInteger());
        if (counter.incrementAndGet() > settings.redstoneThrottleMaxPerSecond) {
            event.setNewCurrent(event.getOldCurrent());
        }
    }
}
