package fi.alavesa.radio.hud;

import fi.alavesa.radio.RadioPlugin;
import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.config.RadioConfig;
import fi.alavesa.radio.item.RadioItem;
import fi.alavesa.radio.item.RadioLocator;
import fi.alavesa.radio.permissions.Perms;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

/**
 * The radio popup: shown while a player carries a powered radio, gone when the radio is off.
 *
 * <p>It is a boss bar per player. For players whose client loaded the pack (and when the pack carries the text
 * shader) the bar's name is the whole popup, laid out by {@link HudText} and moved into the screen corner by the
 * shader; the pack blanks the blue bar graphic. Everyone else gets the same bar with a one-line plain name at the
 * top of the screen, and reads radio messages in chat.
 *
 * <p>Run every few ticks to follow inventories (radio picked up, dropped, moved out of the hotbar) and listener
 * counts; changes the plugin makes itself (power, channel, a message) call {@link #update} at once. Main thread only.
 */
public final class RadioHud implements Runnable, Listener {

    /** One line of the message area: the first {@code nameLength} characters are the sender's name. */
    private record Line(String text, int nameLength, boolean own) {
    }

    private static final class State {
        final ArrayDeque<Line> log = new ArrayDeque<>();
        @Nullable BossBar bar;
        int incomingUntil;
    }

    private final RadioPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public RadioHud(RadioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    /** True when this player sees the popup in the corner (and so does not need radio messages in chat). */
    public boolean fancy(Player p) {
        return plugin.config().popupEnabled && plugin.pack().textShaderShipped() && plugin.pack().loaded(p);
    }

    /** Shows, refreshes or hides the player's popup to match their radio. */
    public void update(Player p) {
        RadioConfig cfg = plugin.config();
        RadioLocator.Found radio = plugin.locator().active(p);
        boolean on = cfg.popupEnabled && radio != null && radio.state().power() && p.hasPermission(Perms.USE);
        State state = states.get(p.getUniqueId());
        if (!on || (!fancy(p) && !cfg.popupPlainFallback)) {
            if (state != null && state.bar != null) {
                p.hideBossBar(state.bar);
                state.bar = null;
            }
            return;
        }
        if (state == null) states.put(p.getUniqueId(), state = new State());
        Component name = fancy(p) ? popup(p, radio.state(), state) : plain(radio.state());
        if (state.bar == null) {
            // BLUE: the bar graphic the pack blanks (no vanilla mob has a blue bar). Progress 0: nothing to fill.
            state.bar = BossBar.bossBar(name, 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            p.showBossBar(state.bar);
        } else if (!state.bar.name().equals(name)) {
            state.bar.name(name);
        }
    }

    /** A transmission the player hears (their own included): into the popup's message area. */
    public void message(Player listener, Player sender, String text) {
        State state = states.computeIfAbsent(listener.getUniqueId(), k -> new State());
        String name = HudText.sanitize(sender.getName());
        List<String> lines = HudText.wrap(name + ": " + text);
        for (int i = 0; i < lines.size(); i++) {
            state.log.addLast(new Line(lines.get(i), i == 0 ? Math.min(name.length() + 1, lines.get(i).length()) : 0, sender.equals(listener)));
        }
        while (state.log.size() > HudText.MESSAGE_LINES) state.log.removeFirst();
        int ticks = plugin.config().outDelayTicks + 10;
        state.incomingUntil = Bukkit.getCurrentTick() + ticks;
        update(listener);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (listener.isOnline()) update(listener);
        }, ticks + 1L);
    }

    private Component popup(Player p, RadioItem.State radio, State state) {
        Messages msg = plugin.messages();
        Channel channel = plugin.channels().get(radio.channel());
        boolean incoming = state.incomingUntil > Bukkit.getCurrentTick();
        HudText t = new HudText().panel(incoming);
        if (incoming) t.lamp();

        t.text(HudText.LINE_HEADER, 0, String.valueOf(HudText.DOT), HudText.GREEN);
        if (channel == null) {
            t.text(HudText.LINE_HEADER, 2, msg.raw("hud.no-channel"), HudText.RED);
        } else {
            String frequency = HudText.sanitize(channel.frequency());
            int column = 2;
            if (!frequency.isEmpty()) {
                t.text(HudText.LINE_HEADER, column, frequency, HudText.WHITE);
                column += frequency.length() + 1;
            }
            // the header's last columns belong to the lamp
            String name = HudText.sanitize(channel.plainName());
            int room = 24 - column;
            t.text(HudText.LINE_HEADER, column, name.length() > room ? name.substring(0, Math.max(0, room)) : name,
                    HudText.nearestPalette(channel.color().value()));
        }

        t.text(HudText.LINE_ID, 0, p.getName(), HudText.WHITE);
        String right = channel == null ? "" : msg.raw("hud.listeners").replace("<count>", String.valueOf(plugin.tracker().listenerCount(channel.id())));
        t.right(HudText.LINE_ID, (right + "  " + msg.raw("hud.volume").replace("<volume>", String.valueOf(radio.volume()))).strip(), HudText.GREY);

        if (state.log.isEmpty()) {
            t.text(HudText.LINE_MESSAGES, 0, msg.raw("hud.no-traffic"), HudText.DIM);
        } else {
            int line = HudText.LINE_MESSAGES;
            for (Line l : new ArrayList<>(state.log)) {
                if (l.nameLength > 0) t.text(line, 0, l.text.substring(0, l.nameLength), l.own ? HudText.CYAN : HudText.AMBER);
                if (l.text.length() > l.nameLength) t.text(line, l.nameLength, l.text.substring(l.nameLength), HudText.GREEN);
                line++;
            }
        }

        boolean canSpeak = channel != null && Perms.canSpeak(p, channel);
        String prefix = plugin.config().publicPrefix;
        String hint1 = !canSpeak ? msg.raw("hud.hint-listen-only") : plugin.config().radioChat ? msg.raw("hud.hint-chat") : msg.raw("hud.hint-say");
        t.text(HudText.LINE_HINT_1, 0, hint1.replace("<prefix>", prefix), canSpeak ? HudText.GREY : HudText.RED);
        t.text(HudText.LINE_HINT_2, 0, msg.raw("hud.hint-controls"), HudText.GREY);
        return t.build();
    }

    private Component plain(RadioItem.State radio) {
        Channel channel = plugin.channels().get(radio.channel());
        return plugin.messages().get("hud.plain",
                Placeholder.component("channel", channel == null ? Component.text("-") : channel.displayName()),
                Placeholder.unparsed("frequency", channel == null ? "" : channel.frequency()),
                Placeholder.unparsed("prefix", plugin.config().publicPrefix));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        State state = states.remove(e.getPlayer().getUniqueId());
        if (state != null && state.bar != null) e.getPlayer().hideBossBar(state.bar);
    }

    /** Hides every popup (plugin disable). */
    public void hideAll() {
        for (var e : states.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && e.getValue().bar != null) p.hideBossBar(e.getValue().bar);
        }
        states.clear();
    }

    /** After a reload: the texts or the layout may have changed. */
    public void refreshAll() {
        run();
    }
}
