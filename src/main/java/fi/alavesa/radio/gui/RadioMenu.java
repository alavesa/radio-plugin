package fi.alavesa.radio.gui;

import fi.alavesa.radio.RadioPlugin;
import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.config.RadioConfig;
import fi.alavesa.radio.item.RadioItem;
import fi.alavesa.radio.permissions.Perms;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * One open radio GUI: a 6-row chest. The same layout serves both looks: "fancy" (the pack's transceiver art as the
 * title, custom icons, empty slots show the art) and "plain" (vanilla items and a text title, for players without
 * the pack). The holder is how the listener recognises the inventory.
 *
 * <pre>
 *  row 0   [power] .  [vol-] [vol] [vol+] .  [mode] .  [talk]
 *  rows 1-4  channel buttons in columns 0-7, [up] and [down] in column 8
 *  row 5   [tuned] .  .  .  .  .  .  .  [close]
 * </pre>
 */
public final class RadioMenu implements InventoryHolder {

    static final int POWER = 0, VOL_DOWN = 2, VOL = 3, VOL_UP = 4, MODE = 6, TALK = 8, UP = 17, DOWN = 44, TUNED = 45, CLOSE = 53;
    static final int COLUMNS = 8, ROWS = 4;

    private final RadioPlugin plugin;
    final Player player;
    final String radioId;
    final boolean fancy;
    final boolean onAir;
    /** The power and channel the title was drawn for: when they change, the menu must be reopened. */
    final boolean titlePower;
    final String titleChannel;
    int scroll;
    private final Inventory inventory;

    RadioMenu(RadioPlugin plugin, Player player, RadioItem.State state, int scroll, boolean fancy, boolean onAir) {
        this.plugin = plugin;
        this.player = player;
        this.radioId = state.id();
        this.fancy = fancy;
        this.onAir = onAir;
        this.titlePower = state.power();
        this.titleChannel = state.channel();
        this.scroll = scroll;
        this.inventory = Bukkit.createInventory(this, 54, title(state));
        render(state);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private Component title(RadioItem.State state) {
        Messages msg = plugin.messages();
        Channel c = plugin.channels().get(state.channel());
        if (fancy) {
            String display = c == null ? msg.raw("gui.lcd-no-channel")
                    : PlainTextComponentSerializer.plainText().serialize(msg.get("gui.lcd-on",
                    Placeholder.unparsed("frequency", c.frequency()), Placeholder.unparsed("channel", c.plainName())));
            return MenuTitle.build(state.power(), onAir, display.strip());
        }
        if (!state.power()) return msg.get("gui.title-off");
        return msg.get("gui.title", Placeholder.component("channel", c == null ? Component.text("-") : c.displayName()));
    }

    /** The channel id behind a channel slot, or null. */
    @Nullable Channel channelAt(int slot) {
        int row = slot / 9 - 1, col = slot % 9;
        if (row < 0 || row >= ROWS || col >= COLUMNS) return null;
        List<Channel> list = Perms.visible(player, plugin.channels());
        int i = (scroll + row) * COLUMNS + col;
        return i < list.size() ? list.get(i) : null;
    }

    int maxScroll() {
        int rows = (Perms.visible(player, plugin.channels()).size() + COLUMNS - 1) / COLUMNS;
        return Math.max(0, rows - ROWS);
    }

    /** Fills every slot from the radio's current state. */
    void render(RadioItem.State state) {
        Messages msg = plugin.messages();
        RadioConfig cfg = plugin.config();
        scroll = RadioConfig.clamp(scroll, 0, maxScroll());
        inventory.clear();
        if (!fancy) {
            ItemStack filler = icon("", Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
            filler.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true));
            for (int i = 0; i < 54; i++) inventory.setItem(i, filler);
        }
        boolean on = state.power();
        inventory.setItem(POWER, icon(on ? "power_on" : "power_off", on ? Material.LIME_DYE : Material.GRAY_DYE,
                msg.item(on ? "gui.power-on" : "gui.power-off"), List.of(msg.item("gui.power-lore"))));
        inventory.setItem(VOL_DOWN, icon("vol_down", Material.STONE_BUTTON, msg.item("gui.volume-down"), List.of()));
        ItemStack vol = icon("volume", Material.NOTE_BLOCK,
                msg.item("gui.volume", Placeholder.unparsed("volume", String.valueOf(state.volume()))), List.of());
        vol.setAmount(Math.max(1, state.volume() / 10));
        inventory.setItem(VOL, vol);
        inventory.setItem(VOL_UP, icon("vol_up", Material.OAK_BUTTON, msg.item("gui.volume-up"), List.of()));
        boolean chat = plugin.players().mode(player, cfg) == RadioConfig.Mode.CHAT;
        inventory.setItem(MODE, icon(chat ? "mode_chat" : "mode_dialog", chat ? Material.WRITABLE_BOOK : Material.OAK_SIGN,
                msg.item(chat ? "gui.mode-chat" : "gui.mode-dialog"), List.of(msg.item("gui.mode-lore"))));
        inventory.setItem(TALK, icon("talk", Material.BELL, msg.item("gui.talk"), List.of(msg.item("gui.talk-lore"))));

        List<Channel> list = Perms.visible(player, plugin.channels());
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int i = (scroll + row) * COLUMNS + col;
                if (i < list.size()) inventory.setItem(9 + row * 9 + col, channelIcon(list.get(i), state));
            }
        }
        if (scroll > 0) inventory.setItem(UP, icon("up", Material.ARROW, msg.item("gui.scroll-up"), List.of()));
        if (scroll < maxScroll()) inventory.setItem(DOWN, icon("down", Material.ARROW, msg.item("gui.scroll-down"), List.of()));

        Channel tuned = plugin.channels().get(state.channel());
        inventory.setItem(TUNED, icon("tuned", Material.COMPASS, tuned == null ? msg.item("gui.current-none")
                : msg.item("gui.current", Placeholder.component("channel", tuned.displayName())), List.of()));
        inventory.setItem(CLOSE, icon("close", Material.BARRIER, msg.item("gui.close"), List.of()));
    }

    private ItemStack channelIcon(Channel c, RadioItem.State state) {
        Messages msg = plugin.messages();
        boolean selected = c.id().equals(state.channel());
        TagResolver tags = TagResolver.resolver(
                Placeholder.component("channel", c.displayName()),
                Placeholder.unparsed("frequency", c.frequency().isEmpty() ? "-" : c.frequency()),
                Placeholder.unparsed("listeners", String.valueOf(plugin.tracker().listenerCount(c.id()))),
                Placeholder.component("listen", msg.get(Perms.canListen(player, c) ? "gui.can-listen" : "gui.cannot-listen")),
                Placeholder.component("speak", msg.get(Perms.canSpeak(player, c) ? "gui.can-speak" : "gui.cannot-speak")),
                Placeholder.component("selected", msg.get(selected ? "gui.selected" : "gui.not-selected")));
        ItemStack stack = icon(selected ? "channel_on" : "channel", c.icon(), msg.item("gui.channel-name", tags),
                msg.itemLines("gui.channel-lore", tags));
        if (fancy) {
            // the button texture is grey; the item model tints it with the channel's colour (dye tint source)
            stack.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(c.color().value())));
            stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.DYED_COLOR));
        } else if (selected) {
            stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    /** A button: the pack's icon model in the fancy GUI, the vanilla material in the plain one. */
    private ItemStack icon(String model, Material plain, Component name, List<Component> lore) {
        ItemStack stack = ItemStack.of(fancy && !model.isEmpty() ? Material.PAPER : plain);
        if (fancy && !model.isEmpty()) stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("radio", "gui/" + model));
        stack.setData(DataComponentTypes.CUSTOM_NAME, name);
        if (!lore.isEmpty()) stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        return stack;
    }
}
