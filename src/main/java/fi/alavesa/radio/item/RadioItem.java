package fi.alavesa.radio.item;

import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.channels.ChannelRegistry;
import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.config.RadioConfig;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The radio item. A radio is recognised by its PersistentDataContainer tag only (never by name or material) and
 * carries its own state there: id, tuned channel, power and volume.
 */
public final class RadioItem {

    /** What one radio stores. {@code channel} is a channel id that may no longer exist. */
    public record State(String id, String channel, boolean power, int volume) {

        public State withChannel(String channel) {
            return new State(id, channel, power, volume);
        }

        public State withPower(boolean power) {
            return new State(id, channel, power, volume);
        }

        public State withVolume(int volume) {
            return new State(id, channel, power, RadioConfig.clamp(volume, 0, 100));
        }
    }

    private final NamespacedKey keyId, keyChannel, keyPower, keyVolume;
    private RadioConfig cfg;
    private Messages msg;
    private ChannelRegistry channels;

    public RadioItem(Plugin plugin, RadioConfig cfg, Messages msg, ChannelRegistry channels) {
        keyId = new NamespacedKey(plugin, "radio");
        keyChannel = new NamespacedKey(plugin, "channel");
        keyPower = new NamespacedKey(plugin, "power");
        keyVolume = new NamespacedKey(plugin, "volume");
        reload(cfg, msg, channels);
    }

    public void reload(RadioConfig cfg, Messages msg, ChannelRegistry channels) {
        this.cfg = cfg;
        this.msg = msg;
        this.channels = channels;
    }

    /** A new radio with its own id, tuned to the global channel. */
    public ItemStack create() {
        ItemStack stack = ItemStack.of(cfg.itemMaterial);
        stack.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        if (!cfg.itemModel.isEmpty()) stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(cfg.itemModel));
        if (!cfg.itemCustomModelData.isEmpty()) {
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(cfg.itemCustomModelData));
        }
        write(stack, new State(UUID.randomUUID().toString(), Channel.GLOBAL, cfg.defaultPower, cfg.defaultVolume));
        return stack;
    }

    public boolean isRadio(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(keyId, PersistentDataType.STRING);
    }

    public @Nullable State read(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        PersistentDataContainerView pdc = stack.getPersistentDataContainer();
        String id = pdc.get(keyId, PersistentDataType.STRING);
        if (id == null) return null;
        return new State(id,
                pdc.getOrDefault(keyChannel, PersistentDataType.STRING, Channel.GLOBAL),
                pdc.getOrDefault(keyPower, PersistentDataType.BOOLEAN, true),
                RadioConfig.clamp(pdc.getOrDefault(keyVolume, PersistentDataType.INTEGER, cfg.defaultVolume), 0, 100));
    }

    /** Stores the state on the stack and refreshes its name and lore to match. */
    public void write(ItemStack stack, State s) {
        stack.editPersistentDataContainer(pdc -> {
            pdc.set(keyId, PersistentDataType.STRING, s.id());
            pdc.set(keyChannel, PersistentDataType.STRING, s.channel());
            pdc.set(keyPower, PersistentDataType.BOOLEAN, s.power());
            pdc.set(keyVolume, PersistentDataType.INTEGER, s.volume());
        });
        Channel c = channels.get(s.channel());
        TagResolver tags = TagResolver.resolver(
                Placeholder.component("channel", c == null ? Component.text(s.channel()) : c.displayName()),
                Placeholder.unparsed("frequency", c == null || c.frequency().isEmpty() ? "-" : c.frequency()),
                Placeholder.component("power", msg.get(s.power() ? "power.on" : "power.off")),
                Placeholder.unparsed("volume", String.valueOf(s.volume())));
        stack.setData(DataComponentTypes.CUSTOM_NAME, Messages.plainStyle(Messages.MM.deserialize(cfg.itemName, tags)));
        List<Component> lore = new ArrayList<>();
        for (String line : cfg.itemLore) lore.add(Messages.plainStyle(Messages.MM.deserialize(line, tags)));
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
    }
}
