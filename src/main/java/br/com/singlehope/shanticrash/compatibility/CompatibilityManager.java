package br.com.singlehope.shanticrash.compatibility;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects the platform and the optional integrations once, at startup.
 *
 * <p>Bedrock players are identified only through the API of Geyser or Floodgate, which the server
 * itself feeds. A UUID prefix or a name prefix could be claimed by anyone on an offline mode server,
 * so neither is used for anything that relaxes a limit.
 */
public final class CompatibilityManager {
    private final String platform;
    private final boolean folia;
    private final boolean viaVersion;
    private final boolean viaBackwards;
    private final boolean viaRewind;
    private final boolean geyser;
    private final boolean floodgate;
    private final boolean protocolLib;

    private Method floodgateInstance;
    private Method floodgateIsPlayer;
    private Method geyserInstance;
    private Method geyserIsPlayer;

    public CompatibilityManager(PluginManager plugins, Logger logger) {
        this.folia = classExists("io.papermc.paper.threadedregions.RegionizedServer");
        this.platform = detectPlatform();
        this.viaVersion = enabled(plugins, "ViaVersion");
        this.viaBackwards = enabled(plugins, "ViaBackwards");
        this.viaRewind = enabled(plugins, "ViaRewind");
        this.geyser = enabled(plugins, "Geyser-Spigot");
        this.floodgate = enabled(plugins, "floodgate");
        this.protocolLib = enabled(plugins, "ProtocolLib");
        resolveBedrockApis(logger);
    }

    private String detectPlatform() {
        if (folia) {
            return "Folia";
        }
        if (classExists("org.purpurmc.purpur.PurpurConfig")) {
            return "Purpur";
        }
        if (classExists("com.destroystokyo.paper.PaperConfig") || classExists("io.papermc.paper.configuration.Configuration")) {
            return "Paper";
        }
        if (classExists("org.spigotmc.SpigotConfig")) {
            return "Spigot";
        }
        return "Desconhecido";
    }

    private static boolean enabled(PluginManager plugins, String name) {
        Plugin plugin = plugins.getPlugin(name);
        return plugin != null;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, CompatibilityManager.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private void resolveBedrockApis(Logger logger) {
        if (floodgate) {
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateInstance = api.getMethod("getInstance");
                floodgateIsPlayer = api.getMethod("isFloodgatePlayer", UUID.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                logger.log(Level.WARNING, "API do Floodgate indisponível: " + e);
            }
        }
        if (geyser) {
            try {
                Class<?> api = Class.forName("org.geysermc.geyser.api.GeyserApi");
                geyserInstance = api.getMethod("api");
                geyserIsPlayer = api.getMethod("isBedrockPlayer", UUID.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                logger.log(Level.WARNING, "API do Geyser indisponível: " + e);
            }
        }
    }

    /** Asks the integrations about a player. Reflection is cached and this runs once per join. */
    public boolean isBedrock(UUID uuid) {
        return query(floodgateInstance, floodgateIsPlayer, uuid) || query(geyserInstance, geyserIsPlayer, uuid);
    }

    private static boolean query(Method instance, Method check, UUID uuid) {
        if (instance == null || check == null) {
            return false;
        }
        try {
            Object api = instance.invoke(null);
            return api != null && Boolean.TRUE.equals(check.invoke(api, uuid));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public String platform() {
        return platform;
    }

    public boolean isFolia() {
        return folia;
    }

    public boolean hasViaVersion() {
        return viaVersion;
    }

    public boolean hasViaBackwards() {
        return viaBackwards;
    }

    public boolean hasViaRewind() {
        return viaRewind;
    }

    public boolean hasGeyser() {
        return geyser;
    }

    public boolean hasFloodgate() {
        return floodgate;
    }

    public boolean hasProtocolLib() {
        return protocolLib;
    }
}
