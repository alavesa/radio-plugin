package fi.alavesa.radio.chat;

import fi.alavesa.radio.RadioPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Radio chat: while a powered radio is with the player, what they type in chat is transmitted on the radio's channel
 * (config chat.radio-chat). A message starting with chat.public-prefix goes to public chat as usual.
 *
 * <p>AsyncChatEvent runs off the main thread, while deciding who hears a channel means reading inventories and
 * permissions, which belongs on the main thread. So a radio message is not delivered by editing the event's viewers:
 * the event is cancelled (nobody receives a public chat line, so there is no duplicate) and the text is handed to
 * the {@link Transmitter} on the next tick. Whether the player carries a powered radio is read from the tracker's
 * thread-safe snapshot.
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
        if (!plugin.config().radioChat || plugin.tracker().tuned(p.getUniqueId()) == null) return;
        String text = Transmitter.plain(e.message());
        String prefix = plugin.config().publicPrefix;
        if (!prefix.isEmpty() && text.startsWith(prefix)) {
            String rest = text.substring(prefix.length()).strip();
            if (rest.isEmpty()) e.setCancelled(true);
            else e.message(Component.text(rest));
            return;
        }
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) plugin.transmitter().transmit(p, null, text);
        });
    }
}
