package fi.alavesa.radio.storage;

import fi.alavesa.radio.config.RadioConfig;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Per-player preferences in plugins/Radio/players/&lt;uuid&gt;.yml: the input mode (dialog or chat). The tuned
 * channel, power and volume live on each radio item, not here. Files are read during the asynchronous pre-login and
 * written on an async task at quit, so the main thread never touches the disk (except on shutdown, where the server
 * is stopping anyway).
 */
public final class PlayerStore implements Listener {

    private final Plugin plugin;
    private final File folder;
    /** null value is not allowed in a ConcurrentHashMap: a missing entry means "server default". */
    private final Map<UUID, RadioConfig.Mode> modes = new ConcurrentHashMap<>();

    public PlayerStore(Plugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "players");
    }

    /** For players already online when the plugin is enabled (a /reload or a plugin manager). */
    public void loadOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) read(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) read(e.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        RadioConfig.Mode mode = modes.remove(id);
        if (plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(id, mode));
    }

    /** Writes everyone still online; called from onDisable. */
    public void saveAll() {
        modes.forEach(this::write);
        modes.clear();
    }

    public RadioConfig.Mode mode(Player p, RadioConfig cfg) {
        return modes.getOrDefault(p.getUniqueId(), cfg.defaultMode);
    }

    public void mode(Player p, RadioConfig.Mode mode) {
        modes.put(p.getUniqueId(), mode);
    }

    private File file(UUID id) {
        return new File(folder, id + ".yml");
    }

    private void read(UUID id) {
        File f = file(id);
        if (!f.isFile()) return;
        String raw = YamlConfiguration.loadConfiguration(f).getString("mode", "");
        try {
            modes.put(id, RadioConfig.Mode.valueOf(raw.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            // unknown or empty: the server default applies
        }
    }

    private synchronized void write(UUID id, RadioConfig.@Nullable Mode mode) {
        File f = file(id);
        if (mode == null) return; // never changed: nothing to store
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("mode", mode.name().toLowerCase(Locale.ROOT));
        try {
            folder.mkdirs();
            yml.save(f);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "could not save " + f.getName(), e);
        }
    }
}
