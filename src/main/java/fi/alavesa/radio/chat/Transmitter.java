package fi.alavesa.radio.chat;

import fi.alavesa.radio.RadioPlugin;
import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.config.RadioConfig;
import fi.alavesa.radio.item.RadioLocator;
import fi.alavesa.radio.permissions.Perms;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Sends a radio message: checks the sender (radio, power, permission, length, rate limit), then delivers the text,
 * the "in" squelch and, a moment later, the "out" beep to every player who hears the channel. Main thread only.
 */
public final class Transmitter {

    private final RadioPlugin plugin;
    private final Map<UUID, Long> lastSent = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> recent = new HashMap<>();

    public Transmitter(RadioPlugin plugin) {
        this.plugin = plugin;
    }

    public void forget(Player p) {
        lastSent.remove(p.getUniqueId());
        recent.remove(p.getUniqueId());
    }

    /**
     * @param channelId the channel to speak on, or null for the channel the sender's radio is tuned to
     * @return true when the message went out
     */
    public boolean transmit(Player sender, @Nullable String channelId, String text) {
        RadioConfig cfg = plugin.config();
        Messages msg = plugin.messages();
        if (!sender.hasPermission(Perms.USE)) {
            msg.send(sender, "error.no-permission");
            return false;
        }
        RadioLocator.Found radio = plugin.locator().active(sender);
        if (radio == null) {
            msg.send(sender, "error.no-radio", Placeholder.component("rule", msg.get("rule." + cfg.carry.name().toLowerCase(Locale.ROOT))));
            return false;
        }
        if (!radio.state().power()) {
            msg.send(sender, "error.radio-off");
            return false;
        }
        Channel channel = plugin.channels().get(channelId == null ? radio.state().channel() : channelId);
        if (channel == null) {
            if (channelId == null) msg.send(sender, "error.no-channel");
            else msg.send(sender, "error.unknown-channel", Placeholder.unparsed("id", channelId));
            return false;
        }
        if (!Perms.canSpeak(sender, channel)) {
            msg.send(sender, "error.cannot-speak", Placeholder.component("channel", channel.displayName()));
            return false;
        }
        String message = text.strip();
        if (message.isEmpty()) {
            msg.send(sender, "error.empty");
            return false;
        }
        if (message.length() > cfg.maxLength) {
            msg.send(sender, "error.too-long", Placeholder.unparsed("length", String.valueOf(message.length())),
                    Placeholder.unparsed("max", String.valueOf(cfg.maxLength)));
            return false;
        }
        if (!rateLimit(sender, cfg, msg)) return false;

        // speaking on another channel than the tuned one retunes the radio, so the answer is heard
        if (!channel.id().equals(radio.state().channel()) && Perms.canListen(sender, channel)) {
            plugin.locator().update(sender, radio, radio.state().withChannel(channel.id()));
        }

        Component line = msg.get("format.message", tags(sender, channel, message, cfg));
        boolean hasLine = !msg.raw("format.message").isEmpty();
        for (Player listener : Bukkit.getOnlinePlayers()) {
            Integer heard = plugin.locator().hearing(listener, plugin.channels()).get(channel.id());
            // the sender always gets their own transmission back, even on a speak-only channel
            if (heard == null && !listener.equals(sender)) continue;
            int volume = heard != null ? heard : radio.state().volume();
            plugin.hud().message(listener, sender, message);
            // the popup shows the message; chat carries it for players without the popup (or when configured)
            if (hasLine && (cfg.popupMessagesInChat || !plugin.hud().fancy(listener))) listener.sendMessage(line);
            plugin.sounds().play(listener, "in", channel, volume);
            plugin.postFx().burst(listener);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (listener.isOnline()) plugin.sounds().play(listener, "out", channel, volume);
            }, cfg.outDelayTicks);
        }
        if (cfg.logToConsole) {
            Bukkit.getConsoleSender().sendMessage(msg.get("format.console", Placeholder.unparsed("id", channel.id()),
                    Placeholder.unparsed("sender", sender.getName()), Placeholder.unparsed("message", message)));
        }
        return true;
    }

    private TagResolver tags(Player sender, Channel channel, String message, RadioConfig cfg) {
        String[] meta = cfg.luckPermsMeta ? plugin.meta(sender) : new String[]{"", ""};
        return TagResolver.resolver(
                Placeholder.component("channel", channel.displayName()),
                Placeholder.styling("channel_color", channel.color()),
                Placeholder.unparsed("frequency", channel.frequency()),
                Placeholder.unparsed("glyph", channel.glyph()),
                Placeholder.unparsed("id", channel.id()),
                Placeholder.component("sender", sender.displayName()),
                Placeholder.component("prefix", legacy(meta[0])),
                Placeholder.component("suffix", legacy(meta[1])),
                // unparsed: whatever a player types is shown as typed, never interpreted as MiniMessage
                Placeholder.unparsed("message", message));
    }

    /** LuckPerms prefixes are usually written with & or § colour codes; MiniMessage tags are accepted too. */
    private static Component legacy(String s) {
        if (s == null || s.isEmpty()) return Component.empty();
        if (s.indexOf('&') >= 0 || s.indexOf('§') >= 0) {
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(s.replace('§', '&'));
        }
        return Messages.MM.deserialize(s);
    }

    private boolean rateLimit(Player sender, RadioConfig cfg, Messages msg) {
        long now = System.currentTimeMillis();
        UUID id = sender.getUniqueId();
        Long last = lastSent.get(id);
        if (cfg.cooldownMs > 0 && last != null && now - last < cfg.cooldownMs) {
            msg.send(sender, "error.cooldown");
            return false;
        }
        if (cfg.maxPerMinute > 0) {
            ArrayDeque<Long> times = recent.computeIfAbsent(id, k -> new ArrayDeque<>());
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.pollFirst();
            if (times.size() >= cfg.maxPerMinute) {
                msg.send(sender, "error.rate-limit");
                return false;
            }
            times.addLast(now);
        }
        lastSent.put(id, now);
        return true;
    }

    /** The plain text of a chat component (what the player typed). */
    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }
}
