package br.com.singlehope.shanticrash.connection;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public final class ProxyDetector {
    private ProxyDetector() {
    }

    public static String detect(File serverRoot) {
        if (enabled(new File(serverRoot, "config/paper-global.yml"), "proxies.velocity.enabled")
                || enabled(new File(serverRoot, "paper.yml"), "settings.velocity-support.enabled")) {
            return "Velocity";
        }
        if (enabled(new File(serverRoot, "spigot.yml"), "settings.bungeecord")) {
            return "BungeeCord";
        }
        return null;
    }

    private static boolean enabled(File file, String path) {
        if (!file.isFile()) {
            return false;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            return yaml.getBoolean(path, false);
        } catch (Exception e) {
            return false;
        }
    }
}
