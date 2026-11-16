package fi.alavesa.radio.sound;

import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.config.RadioConfig;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Plays radio sounds to single players: the pack's custom sound when that player's client loaded the pack, the
 * vanilla fallback otherwise. Nothing is ever played into the world, so nobody nearby hears another player's radio.
 */
public final class RadioSounds {

    private RadioConfig cfg;
    private final Predicate<Player> hasPack;

    public RadioSounds(RadioConfig cfg, Predicate<Player> hasPack) {
        this.cfg = cfg;
        this.hasPack = hasPack;
    }

    public void reload(RadioConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * @param event   one of {@link RadioConfig#SOUND_EVENTS}
     * @param channel the channel whose overrides apply, or null for the global sounds
     * @param volume  the radio's volume, 0-100
     */
    public void play(Player to, String event, @Nullable Channel channel, int volume) {
        SoundSpec spec = channel == null ? null : channel.sounds().get(event);
        if (spec == null) spec = cfg.sounds.get(event);
        if (spec == null || volume <= 0) return;
        String key = hasPack.test(to) && !spec.key().isEmpty() ? spec.key() : spec.fallback();
        float v = spec.volume() * volume / 100f;
        if (key.isEmpty() || v <= 0f) return;
        // Emitter.self(): the sound follows the listener instead of staying where they stood
        to.playSound(Sound.sound(Key.key(key), Sound.Source.PLAYER, v, spec.pitch()), Sound.Emitter.self());
    }
}
