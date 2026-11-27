package fi.alavesa.radio.hud;

import fi.alavesa.radio.RadioPlugin;
import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.config.RadioConfig;
import fi.alavesa.radio.item.RadioItem;
import fi.alavesa.radio.item.RadioLocator;
import fi.alavesa.radio.permissions.Perms;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>The popup is a small device: a header (signal bars, channel, frequency, TX/RX lamp), a strip of tabs - one per
 * channel the player may use - with the active one marked by a sliding pill and unread counts on the others, the
 * message log of the active tab (time, sender, text) and a footer (volume, listeners; the key hints for a few
 * seconds after switching). Switching tabs animates the pill and fades the log in.
 *
 * <p>Run every few ticks to follow inventories and listener counts; changes the plugin makes itself (power, tab, a
 * message) call {@link #update} at once, and an animation steps every tick while it runs. Main thread only.
 */
public final class RadioHud implements Runnable, Listener {

    /** One radio message as kept in a channel's log. */
    private record Msg(String time, String name, String text, boolean own) {
    }

    /** One drawn row of the message area: a message's first row carries the time and name. */
    private record Row(@Nullable Msg head, String text, int column) {
    }

    private static final int LOG_SIZE = 12;
    private static final int NAME_MAX = 8;
    private static final int TEXT_COLUMN = 6;

    private static final class State {
        final Map<String, ArrayDeque<Msg>> logs = new HashMap<>();
        final Map<String, Integer> unread = new HashMap<>();
        @Nullable BossBar bar;
        int incomingUntil;
        long hintUntil;
        /** The pill's left edge in px (it eases towards its tab), or -1 before the first draw. */
        double pillX = -1;
        int tabOffset;
        /** Ticks of "fade in" left after a tab switch: the log is drawn dim, then grey, then normal. */
        int fade;
        @Nullable String lastChannel;
        int lastTarget;
        int lastPillTarget() { return lastTarget; }
    }

    private final RadioPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Set<UUID> animating = new HashSet<>();
    private org.bukkit.scheduler.@Nullable BukkitTask animationTask;

    public RadioHud(RadioPlugin plugin) {
        this.plugin = plugin;
    }

    /** Starts the animation ticker (after the plugin is enabled). */
    public void start() {
        if (animationTask != null) animationTask.cancel();
        animationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 1L, 1L);
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
                state.pillX = -1;
            }
            return;
        }
        if (state == null) states.put(p.getUniqueId(), state = new State());
        if (state.bar == null) showHint(p, state);
        Component name = fancy(p) ? popup(p, radio.state(), state) : plain(radio.state());
        if (state.bar == null) {
            // BLUE: the bar graphic the pack blanks (no vanilla mob has a blue bar). Progress 0: nothing to fill.
            state.bar = BossBar.bossBar(name, 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            p.showBossBar(state.bar);
        } else if (!state.bar.name().equals(name)) {
            state.bar.name(name);
        }
    }

    /** The key hints take the footer for a few seconds (after switching on or changing tab). */
    private void showHint(Player p, State state) {
        int ticks = plugin.config().tabsHintTicks;
        if (ticks <= 0) return;
        state.hintUntil = Bukkit.getCurrentTick() + ticks;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) update(p);
        }, ticks + 1L);
    }

    /** The radio moved to another tab: slide the pill, fade the log in, clear the tab's unread count. */
    public void tabChanged(Player p, String channelId) {
        State state = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        state.unread.remove(channelId);
        state.fade = 2;
        animating.add(p.getUniqueId());
        showHint(p, state);
    }

    /** Eases every moving pill and steps every fade, one tick at a time. */
    private void animate() {
        if (animating.isEmpty()) return;
        for (UUID id : new ArrayList<>(animating)) {
            Player p = Bukkit.getPlayer(id);
            State state = states.get(id);
            if (p == null || state == null || state.bar == null) {
                animating.remove(id);
                continue;
            }
            update(p);
            if (state.fade > 0) state.fade--;
            if (state.fade == 0 && state.pillX >= 0 && Math.abs(state.pillX - state.lastPillTarget()) < 0.5) animating.remove(id);
        }
    }

    /** A transmission the player hears (their own included): into the log of its channel. */
    public void message(Player listener, String channelId, Player sender, String text) {
        message(listener, channelId, sender.getName(), sender.equals(listener), text);
    }

    /** The same for a sender that is not a player ("SYSTEM" announcements from /radio channel say). */
    public void message(Player listener, String channelId, String senderName, boolean own, String text) {
        State state = states.computeIfAbsent(listener.getUniqueId(), k -> new State());
        String time = ZonedDateTime.now(zone()).format(DateTimeFormatter.ofPattern("HH:mm"));
        ArrayDeque<Msg> log = state.logs.computeIfAbsent(channelId, k -> new ArrayDeque<>());
        log.addLast(new Msg(time, HudText.sanitize(senderName), HudText.sanitize(text), own));
        while (log.size() > LOG_SIZE) log.removeFirst();
        RadioLocator.Found radio = plugin.locator().active(listener);
        boolean activeTab = radio != null && radio.state().channel().equals(channelId);
        if (!activeTab && !own) state.unread.merge(channelId, 1, Integer::sum);
        int ticks = plugin.config().outDelayTicks + 10;
        if (activeTab) state.incomingUntil = Bukkit.getCurrentTick() + ticks;
        update(listener);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (listener.isOnline()) update(listener);
        }, ticks + 1L);
    }

    private ZoneId zone() {
        String z = plugin.config().popupTimeZone;
        try {
            return z.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(z);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    // ------------------------------------------------------------------ drawing

    private Component popup(Player p, RadioItem.State radio, State state) {
        Messages msg = plugin.messages();
        List<Channel> tabs = Perms.visible(p, plugin.channels());
        Channel channel = plugin.channels().get(radio.channel());
        int active = -1;
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).id().equals(radio.channel())) active = i;
        if (channel != null && !channel.id().equals(state.lastChannel)) {
            // tuned by a command or another radio: the same animation as the keys
            if (state.lastChannel != null) {
                state.fade = 2;
                animating.add(p.getUniqueId());
            }
            state.lastChannel = channel.id();
            state.unread.remove(channel.id());
        }
        boolean incoming = state.incomingUntil > Bukkit.getCurrentTick();
        HudText t = new HudText().panel(incoming);
        if (incoming) t.lamp();

        // ---- header: signal bars, channel name, frequency
        int count = channel == null ? 0 : plugin.tracker().listenerCount(channel.id());
        int level = count <= 1 ? 1 : count == 2 ? 2 : count <= 4 ? 3 : 4;
        for (int i = 0; i < 4; i++) t.icon(HudText.ROW_HEADER, HudText.TEXT_X + i * HudText.ADVANCE, (char) (HudText.SIGNAL + i), i < level ? HudText.GREEN : HudText.DIM);
        if (channel == null) {
            t.bold(HudText.ROW_HEADER, 5, msg.raw("hud.no-channel"), HudText.RED);
        } else {
            String frequency = HudText.sanitize(channel.frequency());
            if (frequency.length() > 6) frequency = frequency.substring(0, 6);
            int room = 15 - (frequency.isEmpty() ? 0 : frequency.length() + 1);
            String name = HudText.sanitize(channel.plainName());
            if (name.length() > room) name = name.substring(0, Math.max(0, room - 1)) + ".";
            t.bold(HudText.ROW_HEADER, 5, name, HudText.nearestPalette(channel.color().value()));
            if (!frequency.isEmpty()) t.text(HudText.ROW_HEADER, 21 - frequency.length(), frequency, HudText.GREY);
        }

        // ---- tabs
        tabs(t, state, tabs, active);

        // ---- messages
        messages(p, t, state, channel);

        // ---- footer
        long now = Bukkit.getCurrentTick();
        if (state.hintUntil > now && plugin.config().tabsHintTicks > 0) {
            t.text(HudText.ROW_FOOTER, 0, msg.raw("hud.hint-tabs"), HudText.AMBER);
        } else {
            t.text(HudText.ROW_FOOTER, 0, msg.raw("hud.volume-label"), HudText.GREY);
            int lit = (radio.volume() + 19) / 20;
            for (int i = 0; i < 5; i++) t.icon(HudText.ROW_FOOTER, HudText.TEXT_X + (4 + i) * HudText.ADVANCE, HudText.SEGMENT, i < lit ? HudText.WHITE : HudText.DIM);
            String right = msg.raw("hud.listeners").replace("<count>", String.valueOf(count));
            t.right(HudText.ROW_FOOTER, right, HudText.GREY);
        }
        return t.build();
    }

    private void tabs(HudText t, State state, List<Channel> tabs, int active) {
        if (tabs.isEmpty()) return;
        int shown = Math.min(tabs.size(), HudText.TAB_COUNT);
        if (active >= 0) {
            if (active < state.tabOffset) state.tabOffset = active;
            if (active > state.tabOffset + shown - 1) state.tabOffset = active - shown + 1;
        }
        state.tabOffset = Math.max(0, Math.min(state.tabOffset, tabs.size() - shown));
        int slotWidth = HudText.TAB_COLUMNS * HudText.ADVANCE;
        if (active >= 0) {
            double target = pillTarget(state, active);
            state.pillX = state.pillX < 0 ? target : state.pillX + (target - state.pillX) * 0.5;
            if (Math.abs(target - state.pillX) < 0.75) state.pillX = target;
            t.pill((int) Math.round(state.pillX));
        }
        for (int slot = 0; slot < shown; slot++) {
            Channel c = tabs.get(state.tabOffset + slot);
            boolean isActive = state.tabOffset + slot == active;
            int x0 = HudText.TEXT_X + slot * slotWidth + 4;
            int unread = state.unread.getOrDefault(c.id(), 0);
            t.at(HudText.ROW_TABS, x0, c.tabLabel(), isActive ? HudText.WHITE : unread > 0 ? HudText.AMBER : HudText.GREY, isActive || unread > 0);
            if (unread == 1) t.icon(HudText.ROW_TABS, x0 + 4 * HudText.ADVANCE, HudText.UNREAD, HudText.AMBER);
            else if (unread > 1) t.at(HudText.ROW_TABS, x0 + 4 * HudText.ADVANCE, unread > 9 ? "+" : String.valueOf(unread), HudText.AMBER, true);
        }
        // more tabs out of sight: arrows in the margins, amber when something unread is hiding there
        boolean hiddenLeft = false, hiddenRight = false;
        for (int i = 0; i < state.tabOffset; i++) hiddenLeft |= state.unread.getOrDefault(tabs.get(i).id(), 0) > 0;
        for (int i = state.tabOffset + shown; i < tabs.size(); i++) hiddenRight |= state.unread.getOrDefault(tabs.get(i).id(), 0) > 0;
        if (state.tabOffset > 0) t.icon(HudText.ROW_TABS, 0, HudText.LEFT, hiddenLeft ? HudText.AMBER : HudText.DIM);
        if (state.tabOffset + shown < tabs.size()) t.icon(HudText.ROW_TABS, HudText.PANEL_W - 9, HudText.RIGHT, hiddenRight ? HudText.AMBER : HudText.DIM);
    }

    /** The pill's resting x for the active tab (it covers the tab's slot, one px wider than the label area). */
    private int pillTarget(State state, int active) {
        int slot = active - state.tabOffset;
        int x = HudText.TEXT_X + slot * HudText.TAB_COLUMNS * HudText.ADVANCE - 1;
        state.lastTarget = x;
        return x;
    }

    private void messages(Player p, HudText t, State state, @Nullable Channel channel) {
        Messages msg = plugin.messages();
        ArrayDeque<Msg> log = channel == null ? null : state.logs.get(channel.id());
        if (log == null || log.isEmpty()) {
            t.text(HudText.ROW_MESSAGES, 0, msg.raw("hud.no-traffic"), HudText.DIM);
            boolean canSpeak = channel != null && Perms.canSpeak(p, channel);
            String hint = !canSpeak ? msg.raw("hud.hint-listen-only") : plugin.config().radioChat ? msg.raw("hud.hint-chat") : msg.raw("hud.hint-say");
            t.text(HudText.ROW_MESSAGES + 1, 0, hint.replace("<prefix>", plugin.config().publicPrefix), canSpeak ? HudText.DIM : HudText.RED);
            return;
        }
        // whole messages from the newest backwards while they fit; an older message is never shown without its name row
        List<List<Row>> keep = new ArrayList<>();
        int used = 0;
        List<Msg> newestFirst = new ArrayList<>(log);
        java.util.Collections.reverse(newestFirst);
        for (Msg m : newestFirst) {
            String name = m.name.length() > NAME_MAX ? m.name.substring(0, NAME_MAX) : m.name;
            int first = HudText.COLUMNS - (TEXT_COLUMN + name.length() + 1);
            List<String> lines = HudText.wrap(m.text, Math.max(first, 1), HudText.COLUMNS - TEXT_COLUMN);
            if (lines.isEmpty()) lines = List.of("");
            if (used + lines.size() > HudText.MESSAGE_LINES) {
                if (keep.isEmpty()) lines = lines.subList(0, HudText.MESSAGE_LINES); // one long message: its first rows
                else break;
            }
            List<Row> rowsOfMessage = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                rowsOfMessage.add(i == 0 ? new Row(m, lines.get(0), TEXT_COLUMN + name.length() + 1) : new Row(null, lines.get(i), TEXT_COLUMN));
            }
            keep.add(0, rowsOfMessage);
            used += lines.size();
        }
        List<Row> rows = new ArrayList<>();
        for (List<Row> one : keep) rows.addAll(one);
        int from = 0;
        int textPalette = state.fade >= 2 ? HudText.DIM : state.fade == 1 ? HudText.GREY : HudText.WHITE;
        for (int i = from; i < rows.size(); i++) {
            Row r = rows.get(i);
            int row = HudText.ROW_MESSAGES + (i - from);
            if (r.head != null) {
                String shown = r.head.name.length() > NAME_MAX ? r.head.name.substring(0, NAME_MAX) : r.head.name;
                t.text(row, 0, r.head.time, HudText.DIM);
                t.bold(row, TEXT_COLUMN, shown, r.head.own ? HudText.CYAN : HudText.AMBER);
            }
            t.text(row, r.column, r.text, textPalette);
        }
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
        animating.remove(e.getPlayer().getUniqueId());
        State state = states.remove(e.getPlayer().getUniqueId());
        if (state != null && state.bar != null) e.getPlayer().hideBossBar(state.bar);
    }

    /** Hides every popup (plugin disable). */
    public void hideAll() {
        if (animationTask != null) animationTask.cancel();
        animationTask = null;
        animating.clear();
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
