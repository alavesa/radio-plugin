package fi.alavesa.radio.chat;

import fi.alavesa.radio.RadioPlugin;
import fi.alavesa.radio.config.RadioConfig;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Radio chat (input mode "chat"): the player's normal chat goes to their radio channel.
 *
 * <p>AsyncChatEvent runs off the main thread, while deciding who hears a channel means reading inventories and
 * permissions, which belongs on the main thread. So a radio message is not delivered by editing the event's viewers:
 * the event is cancelled (nobody receives the public chat line, so there is no duplicate) and the text is handed to
 * the {@link Transmitter} on the next tick. Whether the player carries a powered radio is read from the tracker's
 * thread-safe snapshot; without one, chat stays ordinary public chat.
 */
public final class ChatListener implements Listener {

    private final RadioPlugin plugin;

    public ChatListener(RadioPlugin plugin) {
        this.plugin = plugin;
    }

    // HIGHEST + ignoreCancelled: mute and filter plugins have had their say before a message becomes a transmission
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (plugin.players().mode(p, plugin.config()) != RadioConfig.Mode.CHAT) return;
        if (plugin.tracker().tuned(p.getUniqueId()) == null) return;
        String text = Transmitter.plain(e.message());
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) plugin.transmitter().transmit(p, null, text);
        });
    }
}
