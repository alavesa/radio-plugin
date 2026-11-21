package fi.alavesa.radio.pack;

import fi.alavesa.radio.config.RadioConfig;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Builds, hosts and sends the Radio pack as an ADDITIONAL server resource pack (Paper multi-pack, replace=false), so
 * the server's own pack keeps working. Tracks, per player, whether our pack (by its fixed UUID) loaded.
 *
 * <p>pack.delivery:
 * <ul>
 *   <li>auto (default): same-port, or http when the Minecraft port cannot be hooked</li>
 *   <li>same-port: served on the Minecraft port itself (nothing to open in a firewall; not behind Velocity/Bungee)</li>
 *   <li>http: served by the built-in HTTP server on pack.http.port (that port must be reachable by players)</li>
 *   <li>url: you upload plugins/Radio/radio-pack.zip yourself; pack.url points at it</li>
 *   <li>none: the pack is merged into another pack; nothing is sent here</li>
 * </ul>
 */
public final class PackService implements Listener {

    /** Fixed id: clients and the status event identify our pack by it across restarts. */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("radio:pack".getBytes(StandardCharsets.UTF_8));

    /**
     * The IdCards pack. Its text shader is built on top of ours (it reads radio-pack.zip and adds its own hooks), so
     * it belongs above the Radio pack: sending ours again after it would only start a tug of war over the top place.
     */
    private static final UUID IDCARDS_PACK_ID = UUID.nameUUIDFromBytes("idcards:pack".getBytes(StandardCharsets.UTF_8));
    /** The SCP079 pack: like IdCards', its text shader is built on top of ours (or of IdCards'), so it belongs above. */
    private static final UUID SCP079_PACK_ID = UUID.nameUUIDFromBytes("scp079:pack".getBytes(StandardCharsets.UTF_8));
    /** The Intercom pack: its text shader is built on top of every other plugin's (ours included), so it belongs above. */
    private static final UUID INTERCOM_PACK_ID = UUID.nameUUIDFromBytes("intercom:pack".getBytes(StandardCharsets.UTF_8));

    /** How often our pack is sent again to stay above a pack that loaded after it, per player and session. */
    private static final int MAX_REPUSHES = 5;
    /** How long no other pack may finish loading before ours is sent again. */
    private static final long SETTLE_TICKS = 60L;

    private final Plugin plugin;
    private final Logger log;
    private final File pluginJar;
    private final Path dataFolder;
    private final Map<UUID, PlayerResourcePackStatusEvent.Status> status = new HashMap<>();
    private RadioConfig cfg;
    private PackBuilder.@Nullable Built built;
    private @Nullable PackHttpServer http;
    private @Nullable SamePortHttp samePort;
    /** What auto resolved to: same-port or http. */
    private String effective = "http";
    private boolean textShader;
    /** Size and time of the other plugins' pack zips at the last build: a change means the merge is stale. */
    private String sourceSignature = "";
    /** Server tick of our last send, and how often we re-sent to stay on top, per player. */
    private final Map<UUID, Integer> sentAt = new HashMap<>(), repushes = new HashMap<>();
    /** Other packs the server pushed to a player after our last send (their ids), per player. */
    private final Map<UUID, Set<UUID>> pushedAfterOurs = new HashMap<>();
    /** The re-send waiting for the other packs to settle, per player. */
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> pending = new HashMap<>();

    private final java.util.function.BiConsumer<Player, PlayerResourcePackStatusEvent.Status> onStatus;

    /** @param onStatus called on the main thread for every status the client reports for our pack */
    public PackService(Plugin plugin, File pluginJar, Path dataFolder, RadioConfig cfg,
                       java.util.function.BiConsumer<Player, PlayerResourcePackStatusEvent.Status> onStatus) {
        this.onStatus = onStatus;
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.pluginJar = pluginJar;
        this.dataFolder = dataFolder;
        this.cfg = cfg;
    }

    public void start() {
        rebuild();
    }

    public void stop() {
        if (http != null) http.stop();
        http = null;
        if (samePort != null) samePort.uninstall();
        samePort = null;
    }

    /** True when the pack carries the (merged) text shader, i.e. the corner popup can be drawn. */
    public boolean textShaderShipped() {
        return textShader;
    }

    /** The delivery in use: same-port, http, url or none. */
    public String delivery() {
        return cfg.packDelivery.equals("auto") ? effective : cfg.packDelivery;
    }

    public void reload(RadioConfig cfg) {
        boolean portChanged = this.cfg.packHttpPort != cfg.packHttpPort || !this.cfg.packHttpBind.equals(cfg.packHttpBind)
                || !cfg.packDelivery.equals(this.cfg.packDelivery);
        this.cfg = cfg;
        if (portChanged) stop();
        String before = built == null ? "" : built.sha1();
        rebuild();
        if (built != null && !built.sha1().equals(before)) {
            for (Player p : Bukkit.getOnlinePlayers()) send(p);
        }
    }

    private File otherPack(String relative) {
        return new File(dataFolder.getParent().toFile(), relative);
    }

    private String signature() {
        StringBuilder sb = new StringBuilder();
        for (String rel : new String[]{cfg.packSuperbVehiclesZip, cfg.packCctvZip}) {
            File f = otherPack(rel);
            sb.append(f.length()).append(':').append(f.lastModified()).append(';');
        }
        return sb.toString();
    }

    /** Called every few seconds: when another plugin rebuilt its pack, merge again and send the new pack out. */
    public void checkSources() {
        if (built == null || !cfg.packTextShader || signature().equals(sourceSignature)) return;
        log.info("another plugin's pack changed: merging the text shader again");
        reload(cfg);
    }

    private String resource(String name) throws IOException {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) throw new IOException(name + " is missing from the plugin jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void rebuild() {
        try {
            Map<String, byte[]> extra = new java.util.LinkedHashMap<>();
            String shader = "off";
            textShader = false;
            sourceSignature = signature();
            if (cfg.packTextShader) {
                TextShaderMerge.Result merged = TextShaderMerge.build(resource("shaders/vanilla_text.vsh"),
                        resource("shaders/vanilla_text.fsh"), otherPack(cfg.packSuperbVehiclesZip), otherPack(cfg.packCctvZip), log::warning);
                extra.putAll(merged.files());
                shader = merged.description();
                textShader = !merged.files().isEmpty();
            }
            // where the popup sits (config popup.*), read by radio_text_vertex.glsl
            extra.put("assets/radio/shaders/include/radio_hud_config.glsl", String.format(java.util.Locale.ROOT,
                    "// Written by the Radio plugin from config.yml (popup.*).%nconst float RADIO_HUD_SCALE = %.3f;%n"
                            + "const float RADIO_HUD_LEFT = %.1f;%nconst vec2 RADIO_HUD_MARGIN = vec2(%d.0, %d.0);%n",
                    cfg.popupScale, cfg.popupLeft ? 1.0 : 0.0, cfg.popupMarginX, cfg.popupMarginTop).getBytes(StandardCharsets.UTF_8));
            Path folder = cfg.packSourceFolder.isBlank() ? null : dataFolder.resolve(cfg.packSourceFolder);
            built = folder != null && Files.isDirectory(folder) ? PackBuilder.fromFolder(folder, extra)
                    : PackBuilder.fromJar(pluginJar, extra);
            Files.createDirectories(dataFolder);
            Files.write(dataFolder.resolve("radio-pack.zip"), built.zip());
            log.info("Radio pack: " + built.files() + " files, " + built.zip().length / 1024 + " KB, sha1 " + built.sha1()
                    + ", text shader: " + shader
                    + (folder != null && Files.isDirectory(folder) ? " (from " + folder + ")" : ""));
        } catch (IOException e) {
            log.log(Level.SEVERE, "could not build the Radio pack", e);
            built = null;
            return;
        }
        if (cfg.packDelivery.equals("same-port") || cfg.packDelivery.equals("auto")) {
            if (samePort == null) {
                SamePortHttp h = new SamePortHttp(Bukkit.getServer(), "radio", path -> dataFolder.resolve("radio-pack.zip").toFile());
                String problem = h.install();
                if (problem == null) {
                    samePort = h;
                    log.info("Radio pack served on the Minecraft port (" + Bukkit.getPort() + "): nothing to open in a firewall");
                } else {
                    log.warning("could not serve the pack on the Minecraft port (" + problem + ")"
                            + (cfg.packDelivery.equals("auto") ? "; using the HTTP server on port " + cfg.packHttpPort : ""));
                }
            }
            effective = samePort != null ? "same-port" : "http";
        }
        if (delivery().equals("http")) {
            try {
                if (http == null) http = new PackHttpServer(cfg.packHttpBind, cfg.packHttpPort);
                http.serve(built.zip(), built.sha1());
            } catch (IOException e) {
                log.log(Level.SEVERE, "could not start the pack HTTP server on " + cfg.packHttpBind + ":"
                        + cfg.packHttpPort + " (port in use? set pack.http.port or pack.delivery: url)", e);
            }
        }
    }

    // ------------------------------------------------------------------ sending

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        forget(p);
        // after the other plugins' packs (they send at join): the pack pushed last is the one on top
        if (cfg.packSendDelayTicks == 0) send(p);
        else Bukkit.getScheduler().runTaskLater(plugin, () -> send(p), cfg.packSendDelayTicks);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        forget(e.getPlayer());
    }

    /**
     * True when a pack may stay above ours: it is one of the plugins whose text shader is built on top of Radio's, AND
     * its zip on disk really contains Radio's hooks right now. Yielding on the id alone broke the popup whenever such a
     * pack had been built before Radio's zip existed or from an old one: the client then ran a text shader without
     * the radio code and drew the popup as raw yellow text at the top of the screen. IdCards 1.1 ships no text
     * shader at all, so its pack can sit anywhere and is simply ignored.
     */
    private boolean carriesOurHooks(UUID id) {
        if (id.equals(IDCARDS_PACK_ID)) return true;
        String zip = id.equals(SCP079_PACK_ID) ? "SCP079/SCP079-pack.zip" : id.equals(INTERCOM_PACK_ID) ? "Intercom/Intercom-pack.zip" : null;
        if (zip == null) return false;
        File f = otherPack(zip);
        if (!f.isFile()) return false;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
            for (java.util.zip.ZipEntry entry : java.util.Collections.list(z.entries())) {
                if (!entry.getName().endsWith("shaders/core/text.vsh")) continue;
                try (InputStream in = z.getInputStream(entry)) {
                    if (new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("radio_text_vertex")) return true;
                }
            }
        } catch (IOException ex) {
            log.fine("could not read " + f + ": " + ex);
        }
        log.info("the " + zip.substring(0, zip.indexOf('/')) + " pack has no radio hooks in its text shader: Radio stays on top for " + "this player");
        return false;
    }

    /** True while a pack that was accepted after our last send has not reported the end of its download yet. */
    private boolean newerStillLoading(Player p) {
        Set<UUID> newer = pushedAfterOurs.get(p.getUniqueId());
        if (newer == null || newer.isEmpty()) return false;
        // they will report SUCCESSFULLY_LOADED (or a failure) and schedule the next attempt themselves
        return true;
    }

    private void forget(Player p) {
        org.bukkit.scheduler.BukkitTask waiting = pending.remove(p.getUniqueId());
        if (waiting != null) waiting.cancel();
        status.remove(p.getUniqueId());
        sentAt.remove(p.getUniqueId());
        repushes.remove(p.getUniqueId());
        pushedAfterOurs.remove(p.getUniqueId());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        if (e.getID().equals(PACK_ID)) {
            // DISCARDED is about the copy an earlier push left behind (we push again to stay on top); it says
            // nothing about the copy the client is using now
            if (e.getStatus() == PlayerResourcePackStatusEvent.Status.DISCARDED) return;
            status.put(p.getUniqueId(), e.getStatus());
            if (e.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
                log.warning(p.getName() + " could not download the Radio pack from " + uri(p) + ". They get the one-line bar "
                        + "instead of the popup. If the URL answers 404, another plugin is answering downloads on the "
                        + "Minecraft port; behind a proxy set pack.delivery: http or url.");
            }
            onStatus.accept(p, e.getStatus());
            return;
        }
        // Another pack was pushed after ours (ACCEPTED is the client's first answer to a push) and has now loaded: it
        // sits above ours, and if it carries its own, un-merged text shader, ours no longer runs. Send ours again.
        // Capped, so two plugins doing the same can never ping-pong forever.
        if (!cfg.packTextShader || !sentAt.containsKey(p.getUniqueId()) || carriesOurHooks(e.getID())) return;
        Set<UUID> newer = pushedAfterOurs.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (e.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD || e.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD
                || e.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED || e.getStatus() == PlayerResourcePackStatusEvent.Status.DISCARDED
                || e.getStatus() == PlayerResourcePackStatusEvent.Status.INVALID_URL) {
            newer.remove(e.getID()); // it will never finish loading: stop waiting for it
        } else if (e.getStatus() == PlayerResourcePackStatusEvent.Status.ACCEPTED) {
            newer.add(e.getID());
        } else if (e.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED && newer.remove(e.getID())) {
            // Wait until the packs have stopped arriving, then send once. Sending after every single pack used the
            // few allowed re-sends up while a slow connection was still downloading the big ones, and whichever
            // pack arrived last then stayed on top with a text shader that lacks the radio's hooks.
            org.bukkit.scheduler.BukkitTask waiting = pending.remove(p.getUniqueId());
            if (waiting != null) waiting.cancel();
            pending.put(p.getUniqueId(), Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pending.remove(p.getUniqueId());
                if (!newerStillLoading(p) && repushes.merge(p.getUniqueId(), 1, Integer::sum) <= MAX_REPUSHES) send(p);
            }, SETTLE_TICKS));
        }
    }

    public void send(Player p) {
        if (built == null || cfg.packDelivery.equals("none") || !p.isOnline()) return;
        URI uri = uri(p);
        if (uri == null) return;
        sentAt.put(p.getUniqueId(), Bukkit.getCurrentTick());
        pushedAfterOurs.remove(p.getUniqueId());
        ResourcePackRequest.Builder req = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, uri, hash()))
                .replace(false)
                .required(cfg.packRequired);
        if (!cfg.packPrompt.isBlank()) req.prompt(MiniMessage.miniMessage().deserialize(cfg.packPrompt));
        p.sendResourcePacks(req.build());
    }

    /** The SHA-1 the client is told: for a self-hosted zip (delivery url) the configured one, when set. */
    private String hash() {
        return cfg.packDelivery.equals("url") && !cfg.packSha1.isEmpty() ? cfg.packSha1 : built.sha1();
    }

    private @Nullable URI uri(Player p) {
        if (cfg.packDelivery.equals("url")) {
            if (cfg.packUrl.isBlank()) {
                log.warning("pack.delivery is url but pack.url is empty; not sending the pack");
                return null;
            }
            return URI.create(cfg.packUrl);
        }
        String host = cfg.packPublicHost;
        if (host.isBlank()) {
            // the host name the player typed to connect is the one that reaches this machine
            InetSocketAddress vh = p.getConnection().getVirtualHost();
            host = vh == null ? "" : vh.getHostString();
            int nul = host.indexOf('\0'); // Forge/FML markers
            if (nul >= 0) host = host.substring(0, nul);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        }
        if (host.isBlank()) host = Bukkit.getIp().isBlank() ? "localhost" : Bukkit.getIp();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]"; // IPv6 literal
        int port = cfg.packHttpPort;
        if (delivery().equals("same-port")) {
            port = Bukkit.getPort();
            InetSocketAddress vh = p.getConnection().getVirtualHost();
            if (cfg.packPublicHost.isBlank() && vh != null && vh.getPort() > 0) port = vh.getPort();
        }
        return URI.create("http://" + host + ":" + port + "/radio/" + built.sha1() + ".zip");
    }

    // ------------------------------------------------------------------ status

    /** True once the client reported our pack as successfully loaded. */
    public boolean loaded(Player p) {
        if (cfg.packDelivery.equals("none")) return true; // merged: assume the owner ships it
        return status.get(p.getUniqueId()) == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
    }

    public PlayerResourcePackStatusEvent.@Nullable Status status(Player p) {
        return status.get(p.getUniqueId());
    }

    public @Nullable String sha1() {
        return built == null ? null : built.sha1();
    }
}
