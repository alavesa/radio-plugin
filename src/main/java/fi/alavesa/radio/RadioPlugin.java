package fi.alavesa.radio;

import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.channels.ChannelRegistry;
import fi.alavesa.radio.chat.ChatListener;
import fi.alavesa.radio.chat.Transmitter;
import fi.alavesa.radio.commands.RadioCommand;
import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.config.RadioConfig;
import fi.alavesa.radio.effects.PostFx;
import fi.alavesa.radio.hud.RadioHud;
import fi.alavesa.radio.integration.LuckPermsHook;
import fi.alavesa.radio.integration.VoiceChatModule;
import fi.alavesa.radio.item.RadioItem;
import fi.alavesa.radio.item.RadioItemListener;
import fi.alavesa.radio.item.RadioLocator;
import fi.alavesa.radio.item.RadioTracker;
import fi.alavesa.radio.pack.PackService;
import fi.alavesa.radio.permissions.Perms;
import fi.alavesa.radio.sound.RadioSounds;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
    private PackService pack;
    private RadioSounds sounds;
    private PostFx postFx;
    private Transmitter transmitter;
    private RadioHud hud;
    private @Nullable LuckPermsHook luckPerms;
    private @Nullable VoiceChatModule voice;
    private @Nullable BukkitTask trackerTask, menuTask, packWatchTask;

    @Override
    public void onEnable() {
        List<String> problems = new ArrayList<>();
        loadFiles(problems);
        perms = new Perms(getServer().getPluginManager());
        perms.sync(channels);

        pack = new PackService(this, getFile(), getDataFolder().toPath(), config, (p, status) -> hud.update(p));
        sounds = new RadioSounds(config, p -> pack.loaded(p));
        postFx = new PostFx(this, config, p -> pack.loaded(p));
        item = new RadioItem(this, config, messages, channels);
        locator = new RadioLocator(item, config);
        tracker = new RadioTracker(locator, postFx, channels);
        transmitter = new Transmitter(this);
        hud = new RadioHud(this);
        itemListener = new RadioItemListener(this);
        itemListener.syncRecipe();

        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(pack, this);
        pm.registerEvents(hud, this);
        pm.registerEvents(new fi.alavesa.radio.item.TabKeys(this), this);
        pm.registerEvents(itemListener, this);
        pm.registerEvents(new ChatListener(this), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                e -> e.registrar().register(new RadioCommand(this).build(), "Radio: power, tune, say, channels, admin", List.of()));

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
        if (hud != null) hud.hideAll();
        if (postFx != null) postFx.clearAll();
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
        // after the tracker in the same tick, so listener counts are fresh
        menuTask = Bukkit.getScheduler().runTaskTimer(this, hud, 21L, 10L);
        hud.start();
        if (packWatchTask == null) packWatchTask = Bukkit.getScheduler().runTaskTimer(this, () -> pack.checkSources(), 100L, 100L);
    }

    private void hookLuckPerms() {
        if (getServer().getPluginManager().getPlugin("LuckPerms") == null) return;
        try {
            LuckPermsHook hook = new LuckPermsHook(this, id -> tracker.tuned(id), () -> channels.ids(), p -> hud.update(p));
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
        hud.refreshAll();
        for (String problem : problems) getLogger().warning(problem);
        return problems;
    }

    /** Channels were edited in game: permissions, contexts and popups follow. */
    public void channelsChanged() {
        perms.sync(channels);
        tracker.run();
        hud.refreshAll();
    }

    /** Switches a radio on or off (or to {@code power} when given), with the click sound; the popup follows. */
    public void togglePower(Player p, RadioLocator.Found radio, @Nullable Boolean power) {
        boolean on = power != null ? power : !radio.state().power();
        RadioItem.State s = radio.state();
        if (on != s.power()) {
            s = locator.update(p, radio, s.withPower(on)).state();
            sounds.play(p, on ? "power-on" : "power-off", channels.get(s.channel()), Math.max(s.volume(), 30));
            tracker.run();
        }
        Channel c = channels.get(s.channel());
        if (!on) messages.send(p, "power.switched-off");
        else if (config.radioChat && c != null) messages.send(p, "power.switched-on", Placeholder.component("channel", c.displayName()));
        else messages.send(p, "power.switched-on-no-chat");
        hud.update(p);
    }

    /** Tunes a radio to a channel the player may listen to or speak on. */
    public void tune(Player p, RadioLocator.Found radio, Channel c) {
        tune(p, radio, c, true);
    }

    /** @param chat false = no line in chat (the popup shows the new tab; used by the tab keys) */
    public void tune(Player p, RadioLocator.Found radio, Channel c, boolean chat) {
        RadioItem.State s = locator.update(p, radio, radio.state().withChannel(c.id())).state();
        sounds.play(p, "switch", c, Math.max(s.volume(), 30));
        if (chat) {
            messages.send(p, "channel.tuned", Placeholder.component("channel", c.displayName()),
                    Placeholder.unparsed("frequency", c.frequency().isEmpty() ? "-" : c.frequency()));
        }
        tracker.run();
        hud.tabChanged(p, c.id());
        hud.update(p);
    }

    /**
     * Moves the radio to another tab (channel): {@code step} +1 / -1 for next / previous, or {@code jump} (1-based) to a
     * tab number. The tabs are the channels the player may use, in channels.yml order.
     */
    public boolean switchTab(Player p, RadioLocator.Found radio, int step, int jump) {
        List<Channel> tabs = fi.alavesa.radio.permissions.Perms.visible(p, channels);
        if (tabs.isEmpty()) return false;
        int at = -1;
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).id().equals(radio.state().channel())) at = i;
        int to = jump > 0 ? jump - 1 : Math.floorMod(at + step, tabs.size());
        if (to < 0 || to >= tabs.size() || to == at) return false;
        tune(p, radio, tabs.get(to), config.tabsChatFeedback);
        return true;
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
    public PackService pack() { return pack; }
    public RadioSounds sounds() { return sounds; }
    public PostFx postFx() { return postFx; }
    public Transmitter transmitter() { return transmitter; }
    public RadioHud hud() { return hud; }
}
