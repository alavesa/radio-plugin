package fi.alavesa.radio.effects;

import fi.alavesa.radio.config.RadioConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Per-player post effects from the pack (assets/&lt;ns&gt;/post_effect/&lt;name&gt;.json), through Paper's
 * {@code Player.postEffects()}, the API behind vanilla {@code /posteffect}. Other plugins (CCTV, Lighting, Firearms)
 * share that list, so this class only ever adds and removes the ids it added itself and never clears.
 *
 * <p>Minecraft 26.3 applies post effects to the 3D frame and draws menus afterwards: an effect is visible in the
 * world and behind an open menu, never on the menu.
 */
public final class PostFx {

    private final Plugin plugin;
    private final Logger log;
    private final Predicate<Player> hasPack;
    private RadioConfig cfg;
    /** What we added, per player, so it can be taken back exactly. */
    private final Map<UUID, Set<String>> applied = new HashMap<>();
    private final Map<UUID, BukkitTask> burstEnd = new HashMap<>();
    private boolean apiBroken;

    public PostFx(Plugin plugin, RadioConfig cfg, Predicate<Player> hasPack) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.cfg = cfg;
        this.hasPack = hasPack;
    }

    public void reload(RadioConfig cfg) {
        clearAll();
        this.cfg = cfg;
    }

    /** The radio GUI was opened or closed. */
    public void gui(Player p, boolean open) {
        set(p, cfg.postGui, open);
    }

    /** A powered radio is carried (or no longer). */
    public void active(Player p, boolean on) {
        set(p, cfg.postActive, on);
    }

    /** A transmission came in: a short burst. */
    public void burst(Player p) {
        String id = cfg.postTransmission;
        if (id.isEmpty() || !usable(p)) return;
        set(p, id, true);
        BukkitTask old = burstEnd.remove(p.getUniqueId());
        if (old != null) old.cancel();
        burstEnd.put(p.getUniqueId(), Bukkit.getScheduler().runTaskLater(plugin, () -> {
            burstEnd.remove(p.getUniqueId());
            // the GUI or active effect may use the same id: keep it then
            if (!id.equals(cfg.postGui) && !id.equals(cfg.postActive)) set(p, id, false);
        }, cfg.postTransmissionTicks));
    }

    private boolean usable(Player p) {
        return cfg.postEnabled && p.isOnline() && hasPack.test(p);
    }

    private void set(Player p, String id, boolean on) {
        if (id.isEmpty()) return;
        Set<String> mine = applied.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (on) {
            if (!usable(p) || !mine.add(id)) return;
            send(p, id, true);
        } else if (mine.remove(id) && p.isOnline()) {
            send(p, id, false);
        }
    }

    private void send(Player p, String id, boolean add) {
        if (!apiBroken) {
            try {
                if (add) p.postEffects().add(Key.key(id));
                else p.postEffects().remove(Key.key(id));
                return;
            } catch (LinkageError | UnsupportedOperationException e) {
                // an older or changed server build: fall back to the vanilla command for the rest of the session
                apiBroken = true;
                log.warning("Player.postEffects() is not available (" + e + "); using the /posteffect command instead");
            }
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:posteffect " + (add ? "add " : "remove ") + p.getName() + " " + id);
    }

    public void forget(Player p) {
        applied.remove(p.getUniqueId());
        BukkitTask t = burstEnd.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }

    /** Removes every effect this plugin added, from everyone online. */
    public void clearAll() {
        for (BukkitTask t : burstEnd.values()) t.cancel();
        burstEnd.clear();
        for (var e : applied.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            for (String id : e.getValue()) send(p, id, false);
        }
        applied.clear();
    }
}
