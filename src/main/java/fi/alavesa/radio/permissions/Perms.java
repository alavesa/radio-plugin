package fi.alavesa.radio.permissions;

import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.channels.ChannelRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * Permission nodes. The fixed ones (radio.use, radio.give, radio.reload, radio.admin) are declared in plugin.yml;
 * the per-channel ones are registered here so they show up in LuckPerms' editor and carry the right defaults:
 * true for "global", false for every other channel. Checks go through {@code hasPermission}, which LuckPerms
 * answers itself when it is installed (including its own wildcard and context handling).
 */
public final class Perms {

    public static final String USE = "radio.use", GIVE = "radio.give", RELOAD = "radio.reload", ADMIN = "radio.admin";
    public static final String ALL_CHANNELS = "radio.channel.*";

    private final PluginManager pm;
    private final Set<String> registered = new HashSet<>();

    public Perms(PluginManager pm) {
        this.pm = pm;
    }

    /** Registers the nodes of every channel and drops those of channels that no longer exist. */
    public void sync(ChannelRegistry channels) {
        for (String name : registered) pm.removePermission(name);
        registered.clear();
        Permission all = new Permission(ALL_CHANNELS, "Listen and speak on every radio channel", PermissionDefault.FALSE);
        for (Channel c : channels.all()) {
            PermissionDefault def = c.isGlobal() ? PermissionDefault.TRUE : PermissionDefault.FALSE;
            String wildcard = "radio.channel." + c.id() + ".*";
            Permission w = new Permission(wildcard, "Listen and speak on the radio channel " + c.id(), def);
            w.getChildren().put(c.listenPermission(), true);
            w.getChildren().put(c.speakPermission(), true);
            add(new Permission(c.listenPermission(), "Hear the radio channel " + c.id(), def));
            add(new Permission(c.speakPermission(), "Speak on the radio channel " + c.id(), def));
            add(w);
            all.getChildren().put(wildcard, true);
        }
        add(all);
        // radio.admin (plugin.yml) lists radio.channel.* as a child: make permissibles pick the new tree up
        Permission admin = pm.getPermission(ADMIN);
        if (admin != null) admin.recalculatePermissibles();
        all.recalculatePermissibles();
    }

    public void unregisterAll() {
        for (String name : registered) pm.removePermission(name);
        registered.clear();
    }

    private void add(Permission p) {
        pm.removePermission(p.getName());
        pm.addPermission(p);
        registered.add(p.getName());
    }

    public static boolean canListen(Player p, Channel c) {
        return p.hasPermission(c.listenPermission());
    }

    public static boolean canSpeak(Player p, Channel c) {
        return p.hasPermission(c.speakPermission());
    }

    /** Channels the player may listen to or speak on, in channels.yml order. */
    public static List<Channel> visible(Player p, ChannelRegistry channels) {
        List<Channel> out = new ArrayList<>();
        for (Channel c : channels.all()) if (canListen(p, c) || canSpeak(p, c)) out.add(c);
        return out;
    }

    public static List<Channel> speakable(Player p, ChannelRegistry channels) {
        List<Channel> out = new ArrayList<>();
        for (Channel c : channels.all()) if (canSpeak(p, c)) out.add(c);
        return out;
    }
}
