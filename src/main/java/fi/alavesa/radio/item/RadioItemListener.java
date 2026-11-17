package fi.alavesa.radio.item;

import fi.alavesa.radio.RadioPlugin;
import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.permissions.Perms;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ShapedRecipe;

/**
 * Using the radio item: right-click switches it on or off, sneak + right-click tunes to the next channel. Also owns the optional
 * crafting recipe (config item.recipe).
 */
public final class RadioItemListener implements Listener {

    private final RadioPlugin plugin;
    private final NamespacedKey recipeKey;
    /** One right-click can fire several interact events (both hands, air and block): act once per tick. */
    private final Map<UUID, Integer> lastUse = new HashMap<>();

    public RadioItemListener(RadioPlugin plugin) {
        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "radio");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() == null || !plugin.item().isRadio(e.getItem())) return;
        // the base material must never do its own thing (place, eat, throw ...)
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        Player p = e.getPlayer();
        Integer last = lastUse.put(p.getUniqueId(), Bukkit.getCurrentTick());
        if (last != null && last == Bukkit.getCurrentTick()) return;
        if (!p.hasPermission(Perms.USE)) {
            plugin.messages().send(p, "error.no-permission");
            return;
        }
        RadioItem.State state = plugin.item().read(e.getItem());
        if (state == null) return;
        RadioLocator.Found radio = new RadioLocator.Found(
                e.getHand() == EquipmentSlot.OFF_HAND ? 40 : p.getInventory().getHeldItemSlot(), e.getItem(), state);
        if (!p.isSneaking()) {
            plugin.togglePower(p, radio, null);
            return;
        }
        // sneak + right-click: the next channel the player may use, wrapping around
        List<Channel> list = Perms.visible(p, plugin.channels());
        if (list.isEmpty()) {
            plugin.messages().send(p, "error.no-channel");
            return;
        }
        int at = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(state.channel())) at = i;
        Channel next = list.get((at + 1) % list.size());
        if (!next.id().equals(state.channel())) plugin.tune(p, radio, next);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.item().isRadio(e.getItemInHand())) e.setCancelled(true);
    }

    /** Every crafted radio gets its own id. */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() instanceof Keyed k && k.getKey().equals(recipeKey)) e.getInventory().setResult(plugin.item().create());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastUse.remove(e.getPlayer().getUniqueId());
    }

    /** Registers or removes the recipe to match the config. */
    public void syncRecipe() {
        Bukkit.removeRecipe(recipeKey);
        if (!plugin.config().recipeEnabled) return;
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, plugin.item().create());
        recipe.shape(plugin.config().recipeShape.toArray(String[]::new));
        plugin.config().recipeIngredients.forEach((ch, material) -> {
            if (String.join("", plugin.config().recipeShape).indexOf(ch) >= 0) recipe.setIngredient(ch, material);
        });
        Bukkit.addRecipe(recipe);
    }

    public void removeRecipe() {
        Bukkit.removeRecipe(recipeKey);
    }
}
