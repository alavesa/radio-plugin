package fi.alavesa.radio.item;

import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.channels.ChannelRegistry;
import fi.alavesa.radio.config.RadioConfig;
import fi.alavesa.radio.permissions.Perms;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/** Finds the radios a player carries and decides which of them work (config radio.works-when). Main thread only. */
public final class RadioLocator {

    /** A radio in a player's inventory. */
    public record Found(int slot, ItemStack stack, RadioItem.State state) {
    }

    private static final int OFF_HAND = 40;

    private final RadioItem item;
    private RadioConfig cfg;

    public RadioLocator(RadioItem item, RadioConfig cfg) {
        this.item = item;
        this.cfg = cfg;
    }

    public void reload(RadioConfig cfg) {
        this.cfg = cfg;
    }

    public RadioItem item() {
        return item;
    }

    /** The radios that work right now: main hand first, then off hand, then the rest of the allowed slots. */
    public List<Found> working(Player p) {
        PlayerInventory inv = p.getInventory();
        List<Found> out = new ArrayList<>(2);
        int held = inv.getHeldItemSlot();
        add(out, inv, held);
        add(out, inv, OFF_HAND);
        int last = switch (cfg.carry) {
            case HELD -> -1;
            case HOTBAR -> 8;
            case INVENTORY -> 35;
        };
        for (int slot = 0; slot <= last; slot++) if (slot != held) add(out, inv, slot);
        return out;
    }

    private void add(List<Found> out, PlayerInventory inv, int slot) {
        ItemStack stack = inv.getItem(slot);
        RadioItem.State s = item.read(stack);
        if (s != null) out.add(new Found(slot, stack, s));
    }

    /** The radio commands and chat act on: the first powered working radio, else the first working radio. */
    public @Nullable Found active(Player p) {
        List<Found> all = working(p);
        for (Found f : all) if (f.state().power()) return f;
        return all.isEmpty() ? null : all.get(0);
    }

    /** A specific radio by its id, among the working radios. */
    public @Nullable Found byId(Player p, String id) {
        for (Found f : working(p)) if (f.state().id().equals(id)) return f;
        return null;
    }

    /** Stores a new state on a found radio and puts it back in its slot. */
    public Found update(Player p, Found f, RadioItem.State s) {
        item.write(f.stack(), s);
        p.getInventory().setItem(f.slot(), f.stack());
        return new Found(f.slot(), f.stack(), s);
    }

    /** Channel id -> volume (0-100) of every channel the player hears right now (powered radio, listen permission). */
    public Map<String, Integer> hearing(Player p, ChannelRegistry channels) {
        Map<String, Integer> out = new HashMap<>(2);
        if (!p.hasPermission(Perms.USE)) return out;
        for (Found f : working(p)) {
            if (!f.state().power()) continue;
            Channel c = channels.get(f.state().channel());
            if (c == null || !Perms.canListen(p, c)) continue;
            out.merge(c.id(), f.state().volume(), Math::max);
        }
        return out;
    }
}
