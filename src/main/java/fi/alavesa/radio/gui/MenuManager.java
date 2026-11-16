package fi.alavesa.radio.gui;

import fi.alavesa.radio.RadioPlugin;
import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.config.RadioConfig;
import fi.alavesa.radio.item.RadioItem;
import fi.alavesa.radio.item.RadioLocator;
import fi.alavesa.radio.permissions.Perms;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

/**
 * Opens radio GUIs, handles their clicks and keeps them current: listener counts every gui.refresh-ticks, at once
 * when permissions change (LuckPerms) or channels are edited, and the ON AIR lamp while a transmission goes through.
 * A chest title cannot be changed in place, so anything that changes the title reopens the menu.
 */
public final class MenuManager implements Listener, Runnable {

    private final RadioPlugin plugin;
    private final Map<UUID, RadioMenu> open = new HashMap<>();
    /** Players whose menu is being replaced by a new one right now: their close event is not a real close. */
    private final Set<UUID> reopening = new HashSet<>();
    /** channel id -> server tick until which its ON AIR lamp is lit. */
    private final Map<String, Integer> onAirUntil = new HashMap<>();

    public MenuManager(RadioPlugin plugin) {
        this.plugin = plugin;
    }

    /** Opens the GUI of the player's active radio, or says why there is none. */
    public void open(Player p) {
        if (!p.hasPermission(Perms.USE)) {
            plugin.messages().send(p, "error.no-permission");
            return;
        }
        RadioLocator.Found radio = plugin.locator().active(p);
        if (radio == null) {
            plugin.messages().send(p, "error.no-radio", Placeholder.component("rule",
                    plugin.messages().get("rule." + plugin.config().carry.name().toLowerCase(Locale.ROOT))));
            return;
        }
        open(p, radio.state(), 0);
    }

    public void open(Player p, RadioItem.State state, int scroll) {
        boolean fancy = plugin.config().guiFancy && plugin.pack().loaded(p);
        boolean onAir = onAirUntil.getOrDefault(state.channel(), 0) > Bukkit.getCurrentTick();
        RadioMenu menu = new RadioMenu(plugin, p, state, scroll, fancy, onAir && state.power());
        reopening.add(p.getUniqueId());
        try {
            p.openInventory(menu.getInventory());
        } finally {
            reopening.remove(p.getUniqueId());
        }
        // another plugin may have cancelled the open
        if (p.getOpenInventory().getTopInventory().getHolder() == menu) {
            open.put(p.getUniqueId(), menu);
            plugin.postFx().gui(p, true);
        } else {
            closed(p);
        }
    }

    private void closed(Player p) {
        open.remove(p.getUniqueId());
        plugin.postFx().gui(p, false);
    }

    private RadioLocator.@Nullable Found radioOf(RadioMenu menu) {
        return plugin.locator().byId(menu.player, menu.radioId);
    }

    // ------------------------------------------------------------------ keeping menus current

    /** Periodic refresh: listener counts, permissions, the radio still being there, the ON AIR lamp going out. */
    @Override
    public void run() {
        int now = Bukkit.getCurrentTick();
        onAirUntil.values().removeIf(until -> until <= now);
        for (RadioMenu menu : new ArrayList<>(open.values())) refresh(menu, false);
    }

    /** Re-renders one player's menu now (their permissions changed). */
    public void refresh(Player p) {
        RadioMenu menu = open.get(p.getUniqueId());
        if (menu != null) refresh(menu, false);
    }

    /** Reopens every menu (channels were edited or the config was reloaded: titles may have changed). */
    public void refreshAll() {
        for (RadioMenu menu : new ArrayList<>(open.values())) refresh(menu, true);
    }

    private void refresh(RadioMenu menu, boolean forceReopen) {
        RadioLocator.Found radio = radioOf(menu);
        if (radio == null || !menu.player.hasPermission(Perms.USE)) {
            menu.player.closeInventory();
            return;
        }
        boolean onAir = radio.state().power() && onAirUntil.getOrDefault(radio.state().channel(), 0) > Bukkit.getCurrentTick();
        boolean titleStale = onAir != menu.onAir || radio.state().power() != menu.titlePower
                || !radio.state().channel().equals(menu.titleChannel);
        if (forceReopen || titleStale) open(menu.player, radio.state(), menu.scroll);
        else menu.render(radio.state());
    }

    /** A transmission went out on the channel: light the ON AIR lamp of the menus tuned to it. */
    public void onAir(Channel channel, int ticks) {
        onAirUntil.put(channel.id(), Bukkit.getCurrentTick() + ticks);
        for (RadioMenu menu : new ArrayList<>(open.values())) {
            if (!menu.fancy || menu.onAir) continue;
            RadioLocator.Found radio = radioOf(menu);
            if (radio != null && radio.state().power() && radio.state().channel().equals(channel.id())) {
                open(menu.player, radio.state(), menu.scroll);
            }
        }
    }

    public void closeAll() {
        for (RadioMenu menu : new ArrayList<>(open.values())) menu.player.closeInventory();
        open.clear();
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof RadioMenu)) return;
        if (e.getPlayer() instanceof Player p && !reopening.contains(p.getUniqueId())) closed(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        open.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof RadioMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RadioMenu menu)) return;
        // nothing moves while the radio GUI is open, in either inventory (the radio item included)
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory() || !(e.getWhoClicked() instanceof Player p)) return;
        if (!e.isLeftClick() && !e.isRightClick()) return;
        RadioLocator.Found radio = radioOf(menu);
        if (radio == null) {
            later(p::closeInventory);
            return;
        }
        RadioItem.State state = radio.state();
        RadioConfig cfg = plugin.config();
        Messages msg = plugin.messages();
        int slot = e.getSlot();
        switch (slot) {
            case RadioMenu.POWER -> {
                state = plugin.locator().update(p, radio, state.withPower(!state.power())).state();
                plugin.sounds().play(p, state.power() ? "power-on" : "power-off", plugin.channels().get(state.channel()), Math.max(state.volume(), 30));
                reopenLater(menu);
            }
            case RadioMenu.VOL_DOWN, RadioMenu.VOL_UP -> {
                int step = slot == RadioMenu.VOL_UP ? cfg.volumeStep : -cfg.volumeStep;
                state = plugin.locator().update(p, radio, state.withVolume(state.volume() + step)).state();
                plugin.sounds().play(p, "switch", plugin.channels().get(state.channel()), state.volume());
                menu.render(state);
            }
            case RadioMenu.MODE -> {
                boolean chat = plugin.players().mode(p, cfg) == RadioConfig.Mode.CHAT;
                plugin.players().mode(p, chat ? RadioConfig.Mode.DIALOG : RadioConfig.Mode.CHAT);
                msg.send(p, chat ? "mode.chat-off" : "mode.chat-on");
                menu.render(state);
            }
            case RadioMenu.TALK -> later(() -> {
                p.closeInventory();
                plugin.dialog().open(p);
            });
            case RadioMenu.UP, RadioMenu.DOWN -> {
                menu.scroll += slot == RadioMenu.UP ? -1 : 1;
                menu.render(state);
            }
            case RadioMenu.CLOSE -> later(p::closeInventory);
            default -> {
                Channel c = menu.channelAt(slot);
                if (c == null || c.id().equals(state.channel())) return;
                if (!Perms.canListen(p, c) && !Perms.canSpeak(p, c)) return;
                state = plugin.locator().update(p, radio, state.withChannel(c.id())).state();
                plugin.sounds().play(p, "switch", c, Math.max(state.volume(), 30));
                msg.send(p, "channel.tuned", Placeholder.component("channel", c.displayName()),
                        Placeholder.unparsed("frequency", c.frequency().isEmpty() ? "-" : c.frequency()));
                reopenLater(menu);
            }
        }
    }

    /** Inventories must not be opened or closed from inside a click event: do it on the next tick. */
    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private void reopenLater(RadioMenu menu) {
        later(() -> {
            if (open.get(menu.player.getUniqueId()) == menu) refresh(menu, true);
        });
    }
}
