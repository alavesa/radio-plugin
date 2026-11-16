package fi.alavesa.radio.channels;

import fi.alavesa.radio.config.RadioConfig;
import fi.alavesa.radio.sound.SoundSpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The channels of channels.yml. All access is on the main thread; saving serialises on the main thread and writes
 * the file on an async task. The channel "global" always exists.
 */
public final class ChannelRegistry {

    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final String HEADER = """
            # Radio channels. Edit here and run /radio reload, or use /radio channel ... in game (this file is rewritten then).
            # <id>: name (MiniMessage), color (#RRGGBB or a name), frequency (label), icon (material, plain GUI),
            #       glyph (text before the name in messages), sounds (overrides of config.yml sounds).
            # The channel "global" always exists. Permissions: radio.channel.<id>.listen / .speak
            """;

    private final Plugin plugin;
    private final File file;
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Object writeLock = new Object();

    public ChannelRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "channels.yml");
    }

    /** (Re)reads channels.yml. Problems are reported through {@code warn}; the bad channel or field is skipped. */
    public void load(RadioConfig cfg, Consumer<String> warn) {
        channels.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("channels");
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                String id = rawId.toLowerCase(Locale.ROOT);
                ConfigurationSection s = root.getConfigurationSection(rawId);
                if (s == null || !ID.matcher(id).matches()) {
                    warn.accept("channels.yml: skipping '" + rawId + "' (ids use a-z, 0-9, _ and -, at most 32 characters)");
                    continue;
                }
                channels.put(id, read(id, s, cfg, warn));
            }
        }
        if (!channels.containsKey(Channel.GLOBAL)) {
            // first in the list, whatever the file says
            Map<String, Channel> rest = new LinkedHashMap<>(channels);
            channels.clear();
            channels.put(Channel.GLOBAL, new Channel(Channel.GLOBAL, "<green>Global", NamedTextColor.GREEN, "100.0",
                    Material.LIME_DYE, "", Map.of(), Map.of()));
            channels.putAll(rest);
            if (file.exists()) warn.accept("channels.yml: the channel 'global' was missing and has been created again");
            save();
        }
    }

    private Channel read(String id, ConfigurationSection s, RadioConfig cfg, Consumer<String> warn) {
        String path = "channels.yml: " + id;
        TextColor color = parseColor(s.getString("color", "white"));
        if (color == null) {
            warn.accept(path + ".color: unknown colour '" + s.getString("color") + "', using white");
            color = NamedTextColor.WHITE;
        }
        Material icon = Material.PAPER;
        if (s.isSet("icon")) icon = RadioConfig.material(s.getString("icon"), Material.PAPER, path + ".icon", warn);
        Map<String, SoundSpec> sounds = new LinkedHashMap<>();
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        ConfigurationSection ss = s.getConfigurationSection("sounds");
        if (ss != null) {
            for (String event : ss.getKeys(false)) {
                ConfigurationSection one = ss.getConfigurationSection(event);
                if (one == null || !RadioConfig.SOUND_EVENTS.contains(event)) {
                    warn.accept(path + ".sounds." + event + ": unknown sound (use " + String.join(", ", RadioConfig.SOUND_EVENTS) + ")");
                    continue;
                }
                sounds.put(event, SoundSpec.read(one, cfg.sounds.get(event), path + ".sounds." + event, warn));
                raw.put(event, new LinkedHashMap<>(one.getValues(false)));
            }
        }
        return new Channel(id, s.getString("name", id), color, s.getString("frequency", ""), icon, s.getString("glyph", ""),
                Map.copyOf(sounds), Collections.unmodifiableMap(raw));
    }

    /** #RRGGBB or a named colour; null when neither. */
    public static @Nullable TextColor parseColor(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.matches("[0-9a-f]{6}")) s = "#" + s;
        return s.startsWith("#") ? TextColor.fromHexString(s) : NamedTextColor.NAMES.value(s);
    }

    // ------------------------------------------------------------------ queries

    public @Nullable Channel get(@Nullable String id) {
        return id == null ? null : channels.get(id);
    }

    public Collection<Channel> all() {
        return Collections.unmodifiableCollection(channels.values());
    }

    public List<String> ids() {
        return List.copyOf(channels.keySet());
    }

    // ------------------------------------------------------------------ edits (persisted)

    public Channel create(String id, String name) {
        Channel c = new Channel(id, name, NamedTextColor.WHITE, "", Material.PAPER, "", Map.of(), Map.of());
        channels.put(id, c);
        save();
        return c;
    }

    public void delete(String id) {
        channels.remove(id);
        save();
    }

    public void replace(Channel c) {
        channels.put(c.id(), c);
        save();
    }

    private String serialise() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Channel c : channels.values()) {
            String p = "channels." + c.id() + ".";
            yml.set(p + "name", c.name());
            yml.set(p + "color", c.color().asHexString().toUpperCase(Locale.ROOT));
            if (!c.frequency().isEmpty()) yml.set(p + "frequency", c.frequency());
            yml.set(p + "icon", c.icon().name());
            if (!c.glyph().isEmpty()) yml.set(p + "glyph", c.glyph());
            for (var e : c.rawSounds().entrySet()) yml.createSection(p + "sounds." + e.getKey(), e.getValue());
        }
        return HEADER + yml.saveToString();
    }

    /** Writes channels.yml off the main thread (through a temp file, so a crash never leaves half a file). */
    public void save() {
        String text = serialise();
        Runnable write = () -> {
            synchronized (writeLock) {
                try {
                    Files.createDirectories(file.getParentFile().toPath());
                    File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                    Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "could not save channels.yml", e);
                }
            }
        };
        if (plugin.isEnabled()) plugin.getServer().getScheduler().runTaskAsynchronously(plugin, write);
        else write.run();
    }
}
