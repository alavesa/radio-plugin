package fi.alavesa.radio.item;

import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.channels.ChannelRegistry;
import fi.alavesa.radio.effects.PostFx;
import fi.alavesa.radio.permissions.Perms;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * A snapshot, refreshed every few ticks on the main thread, of who listens to which channel. It feeds the things that
 * must not scan inventories themselves: the GUI's listener counts, the LuckPerms context and the voice module (both
 * of which are called from other threads, hence the concurrent/immutable views).
 */
public final class RadioTracker implements Runnable {

    private final RadioLocator locator;
    private final PostFx postFx;
    private ChannelRegistry channels;

    /** channel id -> listeners. Replaced as a whole on every refresh; the sets are immutable. */
    private volatile Map<String, Set<UUID>> listeners = Map.of();
    /** player -> the channel of their active powered radio (the LuckPerms context value). */
    private final Map<UUID, String> tuned = new ConcurrentHashMap<>();
    /** player -> the channel their voice goes to: a powered radio in the main hand, speak permission. */
    private final Map<UUID, String> voice = new ConcurrentHashMap<>();
    private @Nullable Consumer<Player> onTunedChange;

    public RadioTracker(RadioLocator locator, PostFx postFx, ChannelRegistry channels) {
        this.locator = locator;
        this.postFx = postFx;
        this.channels = channels;
    }

    public void reload(ChannelRegistry channels) {
        this.channels = channels;
    }

    /** Called (main thread) when a player's tuned channel changed; LuckPerms re-evaluates its contexts then. */
    public void onTunedChange(@Nullable Consumer<Player> callback) {
        this.onTunedChange = callback;
    }

    @Override
    public void run() {
        Map<String, Set<UUID>> next = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            for (String channel : locator.hearing(p, channels).keySet()) {
                next.computeIfAbsent(channel, k -> new HashSet<>()).add(id);
            }
            RadioLocator.Found active = locator.active(p);
            boolean powered = active != null && active.state().power() && p.hasPermission(Perms.USE);
            String now = powered ? active.state().channel() : null;
            String before = now == null ? tuned.remove(id) : tuned.put(id, now);
            if (!Objects.equals(before, now) && onTunedChange != null) onTunedChange.accept(p);
            postFx.active(p, powered);

            RadioItem.State hand = locator.item().read(p.getInventory().getItemInMainHand());
            Channel vc = hand != null && hand.power() ? channels.get(hand.channel()) : null;
            if (vc != null && p.hasPermission(Perms.USE) && Perms.canSpeak(p, vc)) voice.put(id, vc.id());
            else voice.remove(id);
        }
        next.replaceAll((k, v) -> Set.copyOf(v));
        listeners = Map.copyOf(next);
    }

    public void forget(Player p) {
        tuned.remove(p.getUniqueId());
        voice.remove(p.getUniqueId());
    }

    /** How many players hear the channel (as of the last refresh). */
    public int listenerCount(String channel) {
        return listeners.getOrDefault(channel, Set.of()).size();
    }

    /** Thread-safe. */
    public Set<UUID> listeners(String channel) {
        return listeners.getOrDefault(channel, Set.of());
    }

    /** Thread-safe: the channel of the player's active powered radio, or null. */
    public @Nullable String tuned(UUID player) {
        return tuned.get(player);
    }

    /** Thread-safe: the channel the player's voice is transmitted on right now, or null. */
    public @Nullable String voiceChannel(UUID player) {
        return voice.get(player);
    }
}
