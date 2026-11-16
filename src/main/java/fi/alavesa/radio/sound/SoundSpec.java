package fi.alavesa.radio.sound;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * One configurable sound: the custom key from the resource pack, the vanilla key used when the listener has no pack,
 * volume and pitch. An empty key means silence.
 */
public record SoundSpec(String key, String fallback, float volume, float pitch) {

    public static final SoundSpec SILENT = new SoundSpec("", "", 1f, 1f);

    /** Reads a sound section; every field that is missing or invalid is taken from {@code base}. */
    public static SoundSpec read(ConfigurationSection s, SoundSpec base, String path, Consumer<String> warn) {
        if (s == null) return base;
        String key = soundKey(s.getString("key"), base.key, path + ".key", warn);
        String fallback = soundKey(s.getString("fallback"), base.fallback, path + ".fallback", warn);
        float volume = (float) Math.max(0, Math.min(4, s.getDouble("volume", base.volume)));
        float pitch = (float) Math.max(0.5, Math.min(2, s.getDouble("pitch", base.pitch)));
        return new SoundSpec(key, fallback, volume, pitch);
    }

    private static String soundKey(String raw, String def, String path, Consumer<String> warn) {
        if (raw == null) return def;
        String s = raw.trim();
        if (s.isEmpty() || s.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) return s;
        warn.accept(path + ": '" + raw + "' is not a sound id, keeping '" + def + "'");
        return def;
    }
}
