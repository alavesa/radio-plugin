package fi.alavesa.radio.item;

import fi.alavesa.radio.RadioPlugin;
import fi.alavesa.radio.permissions.Perms;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.jspecify.annotations.Nullable;

/**
 * Switching radio tabs (channels) with keys, while a powered radio is in the hand. A server cannot read arbitrary keys,
 * so these two events carry them:
 * <ul>
 *   <li><b>F</b> (swap hands): next tab, <b>sneak + F</b>: previous tab. The swap itself is cancelled.</li>
 *   <li><b>Sneak + scroll wheel</b>: next / previous tab; <b>sneak + a number key</b> (1-9) that is not the neighbouring
 *       slot: that tab number. The hotbar does not move.</li>
 * </ul>
 * Both can be switched off in config.yml (tabs.keys). Nothing happens when the radio is off or not in a hand, so the
 * keys keep their normal meaning for everyone else.
 */
public final class TabKeys implements Listener {

    private final RadioPlugin plugin;

    public TabKeys(RadioPlugin plugin) {
        this.plugin = plugin;
    }

    /** The powered radio in the main hand, or null. */
    private RadioLocator.@Nullable Found handRadio(Player p, int slot) {
        if (!p.hasPermission(Perms.USE)) return null;
        var stack = p.getInventory().getItem(slot);
        RadioItem.State s = plugin.item().read(stack);
        return s != null && s.power() ? new RadioLocator.Found(slot, stack, s) : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!plugin.config().tabsSwapHand) return;
        Player p = e.getPlayer();
        RadioLocator.Found radio = handRadio(p, p.getInventory().getHeldItemSlot());
        if (radio == null) return;
        e.setCancelled(true);
        plugin.switchTab(p, radio, p.isSneaking() ? -1 : 1, 0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        if (!plugin.config().tabsSneakScroll) return;
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;
        // the radio is in the slot the player is leaving
        RadioLocator.Found radio = handRadio(p, e.getPreviousSlot());
        if (radio == null) return;
        e.setCancelled(true);
        int delta = Math.floorMod(e.getNewSlot() - e.getPreviousSlot(), 9);
        if (delta == 1) plugin.switchTab(p, radio, 1, 0);
        else if (delta == 8) plugin.switchTab(p, radio, -1, 0);
        else plugin.switchTab(p, radio, 0, e.getNewSlot() + 1);
    }
}
