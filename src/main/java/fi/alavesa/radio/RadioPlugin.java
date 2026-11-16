package fi.alavesa.radio;

import fi.alavesa.radio.channels.ChannelRegistry;
import fi.alavesa.radio.chat.ChatListener;
import fi.alavesa.radio.chat.Transmitter;
import fi.alavesa.radio.commands.RadioCommand;
import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.config.RadioConfig;
import fi.alavesa.radio.dialog.TalkDialog;
import fi.alavesa.radio.effects.PostFx;
import fi.alavesa.radio.gui.MenuManager;
import fi.alavesa.radio.integration.LuckPermsHook;
import fi.alavesa.radio.integration.VoiceChatModule;
import fi.alavesa.radio.item.RadioItem;
import fi.alavesa.radio.item.RadioItemListener;
import fi.alavesa.radio.item.RadioLocator;
import fi.alavesa.radio.item.RadioTracker;
import fi.alavesa.radio.pack.PackService;
import fi.alavesa.radio.permissions.Perms;
import fi.alavesa.radio.sound.RadioSounds;
import fi.alavesa.radio.storage.PlayerStore;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/** Wires the parts together and owns the reload. Everything here runs on the main thread. */
public final class RadioPlugin extends JavaPlugin implements Listener {

    private RadioConfig config;
    private Messages messages;
    private ChannelRegistry channels;
    private Perms perms;
    private RadioItem item;
    private RadioLocator locator;
    private RadioTracker tracker;
    private RadioItemListener itemListener;
    private PlayerStore players;
    private PackService pack;
    private RadioSounds sounds;
    private PostFx postFx;
    private Transmitter transmitter;
    private MenuManager menus;
    private TalkDialog dialog;
    private @Nullable LuckPermsHook luckPerms;
    private @Nullable VoiceChatModule voice;
    private @Nullable BukkitTask trackerTask, menuTask, packWatchTask;

    @Override
    public void onEnable() {
        List<String> problems = new ArrayList<>();
        loadFiles(problems);
        perms = new Perms(getServer().getPluginManager());
        perms.sync(channels);

        pack = new PackService(this, getFile(), getDataFolder().toPath(), config, (p, status) -> menus.refresh(p));
        sounds = new RadioSounds(config, p -> pack.loaded(p));
        postFx = new PostFx(this, config, p -> pack.loaded(p));
        item = new RadioItem(this, config, messages, channels);
        locator = new RadioLocator(item, config);
        tracker = new RadioTracker(locator, postFx, channels);
        players = new PlayerStore(this);
        players.loadOnline();
        transmitter = new Transmitter(this);
        menus = new MenuManager(this);
        dialog = new TalkDialog(this);
        itemListener = new RadioItemListener(this);
        itemListener.syncRecipe();

        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(pack, this);
        pm.registerEvents(players, this);
        pm.registerEvents(menus, this);
        pm.registerEvents(itemListener, this);
        pm.registerEvents(new ChatListener(this), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                e -> e.registrar().register(new RadioCommand(this).build(), "Radio: open, talk, channels, admin", List.of()));

        hookLuckPerms();
        hookVoiceChat();
        startTasks();
        // the pack is built and hosted once the server is listening (same-port needs the Minecraft channel)
        Bukkit.getScheduler().runTask(this, () -> {
            pack.start();
            for (Player p : Bukkit.getOnlinePlayers()) pack.send(p);
        });
        for (String problem : problems) getLogger().warning(problem);
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.closeAll();
        if (postFx != null) postFx.clearAll();
        if (players != null) players.saveAll();
        if (pack != null) pack.stop();
        if (itemListener != null) itemListener.removeRecipe();
        if (perms != null) perms.unregisterAll();
        if (voice != null) voice.enabled(false);
        if (luckPerms != null) luckPerms.close();
        luckPerms = null;
        Bukkit.getScheduler().cancelTasks(this);
    }

    private void loadFiles(List<String> problems) {
        saveDefaultConfig();
        reloadConfig();
        config = new RadioConfig(getConfig(), problems::add);
        File mf = new File(getDataFolder(), "messages.yml");
        if (!mf.exists()) saveResource("messages.yml", false);
        YamlConfiguration m = YamlConfiguration.loadConfiguration(mf);
        try (var in = getResource("messages.yml")) {
            if (in != null) m.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "could not read the built-in messages.yml", e);
        }
        messages = new Messages(m);
        if (!new File(getDataFolder(), "channels.yml").exists()) saveResource("channels.yml", false);
        if (channels == null) channels = new ChannelRegistry(this);
        channels.load(config, problems::add);
    }

    private void startTasks() {
        if (trackerTask != null) trackerTask.cancel();
        if (menuTask != null) menuTask.cancel();
        trackerTask = Bukkit.getScheduler().runTaskTimer(this, tracker, 20L, 10L);
        menuTask = Bukkit.getScheduler().runTaskTimer(this, menus, 20L, config.guiRefreshTicks);
        if (packWatchTask == null) packWatchTask = Bukkit.getScheduler().runTaskTimer(this, () -> pack.checkSources(), 100L, 100L);
    }

    private void hookLuckPerms() {
        if (getServer().getPluginManager().getPlugin("LuckPerms") == null) return;
        try {
            LuckPermsHook hook = new LuckPermsHook(this, id -> tracker.tuned(id), () -> channels.ids(), p -> menus.refresh(p));
            luckPerms = hook;
            tracker.onTunedChange(hook::contextChanged);
            getLogger().info("LuckPerms found: context '" + LuckPermsHook.CONTEXT + "', live permission refresh, prefix/suffix");
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "LuckPerms is installed but could not be hooked; using plain permissions", t);
        }
    }

    private void hookVoiceChat() {
        if (!config.voiceEnabled || voice != null || getServer().getPluginManager().getPlugin("voicechat") == null) return;
        try {
            voice = VoiceChatModule.register(tracker);
            if (voice != null) getLogger().info("Simple Voice Chat found: EXPERIMENTAL radio voice module enabled");
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Simple Voice Chat is installed but could not be hooked", t);
        }
    }

    /** /radio reload. @return the warnings (also logged) */
    public List<String> reload() {
        List<String> problems = new ArrayList<>();
        loadFiles(problems);
        perms.sync(channels);
        item.reload(config, messages, channels);
        locator.reload(config);
        tracker.reload(channels);
        sounds.reload(config);
        postFx.reload(config);
        pack.reload(config);
        itemListener.syncRecipe();
        hookVoiceChat();
        if (voice != null) voice.enabled(config.voiceEnabled);
        startTasks();
        tracker.run();
        menus.refreshAll();
        for (String problem : problems) getLogger().warning(problem);
        return problems;
    }

    /** Channels were edited in game: permissions, contexts and open menus follow. */
    public void channelsChanged() {
        perms.sync(channels);
        tracker.run();
        menus.refreshAll();
    }

    /**
     * Switches a radio on or off (or to {@code power} when given), with the click sound.
     */
    public void togglePower(Player p, RadioLocator.Found radio, @Nullable Boolean power) {
        boolean on = power != null ? power : !radio.state().power();
        if (on != radio.state().power()) {
            RadioItem.State s = locator.update(p, radio, radio.state().withPower(on)).state();
            sounds.play(p, on ? "power-on" : "power-off", channels.get(s.channel()), Math.max(s.volume(), 30));
            tracker.run();
        }
        messages.send(p, on ? "power.switched-on" : "power.switched-off");
        menus.refresh(p);
    }

    /** {prefix, suffix} from LuckPerms, or two empty strings. */
    public String[] meta(Player p) {
        return luckPerms == null ? new String[]{"", ""} : luckPerms.meta(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        tracker.forget(p);
        postFx.forget(p);
        transmitter.forget(p);
    }

    public RadioConfig config() { return config; }
    public Messages messages() { return messages; }
    public ChannelRegistry channels() { return channels; }
    public RadioItem item() { return item; }
    public RadioLocator locator() { return locator; }
    public RadioTracker tracker() { return tracker; }
    public PlayerStore players() { return players; }
    public PackService pack() { return pack; }
    public RadioSounds sounds() { return sounds; }
    public PostFx postFx() { return postFx; }
    public Transmitter transmitter() { return transmitter; }
    public MenuManager menus() { return menus; }
    public TalkDialog dialog() { return dialog; }
}
