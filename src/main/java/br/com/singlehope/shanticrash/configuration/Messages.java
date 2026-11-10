package br.com.singlehope.shanticrash.configuration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;

/**
 * The texts shown to people. Everything is MiniMessage and placeholders are resolved before parsing,
 * with tags escaped: a player name or a value from a packet can never inject formatting.
 */
public final class Messages {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private final ConfigurationManager configuration;
    private YamlConfiguration file;
    private String prefix = "";

    public Messages(JavaPlugin plugin, ConfigurationManager configuration) {
        this.plugin = plugin;
        this.configuration = configuration;
    }

    public void load(String language) {
        String name = "messages_" + language + ".yml";
        File target = new File(plugin.getDataFolder(), name);
        if (!target.exists()) {
            if (plugin.getResource(name) != null) {
                plugin.saveResource(name, false);
            } else {
                plugin.getLogger().warning("Idioma sem arquivo: " + language + ", usando pt-BR");
                name = "messages_pt-BR.yml";
                target = new File(plugin.getDataFolder(), name);
                if (!target.exists()) {
                    plugin.saveResource(name, false);
                }
            }
        }
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.loadFromString(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, name + " inválido, usando os textos internos: " + e.getMessage());
        }
        YamlConfiguration defaults = configuration.loadResource("messages_pt-BR.yml");
        if (defaults != null) {
            loaded.setDefaults(defaults);
        }
        this.file = loaded;
        this.prefix = loaded.getString("prefix", "");
    }

    public String raw(String key) {
        return file.getString(key, key);
    }

    public List<String> rawList(String key) {
        return file.getStringList(key);
    }

    /** Escapes text from outside so that it is shown literally. */
    public static String escape(String value) {
        return value == null ? "" : MINI.escapeTags(value.replace('§', '?'));
    }

    /**
     * Resolves {@code %prefix%} and the given pairs of placeholder and value.
     *
     * @param pairs alternating names (without the percent signs) and values, which get escaped
     */
    public String apply(String template, String... pairs) {
        String text = template.replace("%prefix%", prefix);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("%" + pairs[i] + "%", escape(pairs[i + 1]));
        }
        return text;
    }

    public Component component(String key, String... pairs) {
        return parse(apply(raw(key), pairs));
    }

    public static Component parse(String miniMessage) {
        try {
            return MINI.deserialize(miniMessage);
        } catch (RuntimeException e) {
            try {
                return MINI.deserialize(miniMessage.replace('§', '&'));
            } catch (RuntimeException ignored) {
                return Component.text(miniMessage.replaceAll("<[^>]*>", "").replace('§', '&'));
            }
        }
    }

    public static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    public static String plain(Component component) {
        return legacy(component).replaceAll("§.", "");
    }
}
