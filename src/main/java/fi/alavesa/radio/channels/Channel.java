package fi.alavesa.radio.channels;

import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.sound.SoundSpec;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;

/**
 * One radio channel.
 *
 * @param id         permission-safe id (a-z, 0-9, _ and -)
 * @param name       display name, MiniMessage
 * @param frequency  free label such as "101.5", may be empty
 * @param icon       a material for the channel (not shown anywhere since the popup replaced the chest GUI; kept so old files load)
 * @param glyph      text shown before the name in messages, may be empty
 * @param sounds     sound events this channel overrides, already merged with the global sound (event -> sound)
 * @param rawSounds  the overrides exactly as written in channels.yml, kept so saving does not lose them
 */
public record Channel(String id, String name, TextColor color, String frequency, Material icon, String glyph,
                      Map<String, SoundSpec> sounds, Map<String, Map<String, Object>> rawSounds) {

    public static final String GLOBAL = "global";

    public Component displayName() {
        return Messages.MM.deserialize(name).colorIfAbsent(color);
    }

    /** The name without any formatting (for the popup and the console). */
    public String plainName() {
        return PlainTextComponentSerializer.plainText().serialize(Messages.MM.deserialize(name));
    }

    public boolean isGlobal() {
        return GLOBAL.equals(id);
    }

    public String listenPermission() {
        return "radio.channel." + id + ".listen";
    }

    public String speakPermission() {
        return "radio.channel." + id + ".speak";
    }

    public Channel withName(String name) {
        return new Channel(id, name, color, frequency, icon, glyph, sounds, rawSounds);
    }

    public Channel withColor(TextColor color) {
        return new Channel(id, name, color, frequency, icon, glyph, sounds, rawSounds);
    }

    public Channel withFrequency(String frequency) {
        return new Channel(id, name, color, frequency, icon, glyph, sounds, rawSounds);
    }
}
