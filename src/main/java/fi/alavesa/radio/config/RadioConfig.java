package fi.alavesa.radio.config;

import fi.alavesa.radio.sound.SoundSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** An immutable snapshot of config.yml. Invalid values fall back to the default and are reported through {@code warn}. */
public final class RadioConfig {

    /** When a carried radio works. */
    public enum Carry { HELD, HOTBAR, INVENTORY }

    /** The sound events of config.yml {@code sounds} (and of a channel's overrides). */
    public static final List<String> SOUND_EVENTS = List.of("in", "out", "power-on", "power-off", "switch");

    public final Carry carry;
    public final int defaultVolume, volumeStep;
    public final boolean defaultPower;

    public final Material itemMaterial;
    public final String itemName, itemModel, itemCustomModelData;
    public final List<String> itemLore;
    public final boolean recipeEnabled;
    public final List<String> recipeShape;
    public final Map<Character, Material> recipeIngredients;

    public final int maxLength, maxPerMinute;
    public final long cooldownMs;
    public final boolean logToConsole, luckPermsMeta, radioChat;
    public final String publicPrefix;

    public final Map<String, SoundSpec> sounds;
    public final int outDelayTicks;

    public final boolean popupEnabled, popupLeft, popupPlainFallback, popupMessagesInChat;
    public final double popupScale;
    public final int popupMarginX, popupMarginTop;

    public final boolean postEnabled;
    public final String postTransmission, postActive;
    public final int postTransmissionTicks;

    public final String packDelivery, packUrl, packSha1, packPrompt, packPublicHost, packHttpBind, packSourceFolder;
    public final boolean packRequired, packTextShader;
    public final int packHttpPort, packSendDelayTicks;
    public final String packSuperbVehiclesZip, packCctvZip;

    public final boolean voiceEnabled;

    public RadioConfig(FileConfiguration c, Consumer<String> warn) {
        carry = enumOf(Carry.class, c.getString("radio.works-when", "hotbar"), Carry.HOTBAR, "radio.works-when", warn);
        defaultVolume = clamp(c.getInt("radio.default-volume", 80), 0, 100);
        volumeStep = clamp(c.getInt("radio.volume-step", 10), 1, 100);
        defaultPower = c.getBoolean("radio.default-power", true);

        itemMaterial = material(c.getString("item.material", "PAPER"), Material.PAPER, "item.material", warn);
        itemName = c.getString("item.name", "<gold>Handheld Radio");
        itemLore = List.copyOf(c.getStringList("item.lore"));
        itemModel = key(c.getString("item.model", ""), "item.model", warn);
        itemCustomModelData = c.getString("item.custom-model-data", "");
        recipeShape = List.copyOf(c.getStringList("item.recipe.shape"));
        Map<Character, Material> ing = new LinkedHashMap<>();
        ConfigurationSection is = c.getConfigurationSection("item.recipe.ingredients");
        if (is != null) {
            for (String k : is.getKeys(false)) {
                Material m = material(is.getString(k, ""), null, "item.recipe.ingredients." + k, warn);
                if (k.length() != 1) warn.accept("item.recipe.ingredients." + k + ": the key must be one character");
                else if (m != null) ing.put(k.charAt(0), m);
            }
        }
        recipeIngredients = Map.copyOf(ing);
        recipeEnabled = c.getBoolean("item.recipe.enabled", false) && validRecipe(warn);

        maxLength = clamp(c.getInt("chat.max-length", 200), 1, 256);
        cooldownMs = Math.max(0, c.getLong("chat.cooldown-ms", 1500));
        maxPerMinute = Math.max(0, c.getInt("chat.max-per-minute", 20));
        logToConsole = c.getBoolean("chat.log-to-console", true);
        luckPermsMeta = c.getBoolean("chat.luckperms-meta", true);
        radioChat = c.getBoolean("chat.radio-chat", true);
        publicPrefix = c.getString("chat.public-prefix", "!").strip();

        Map<String, SoundSpec> s = new LinkedHashMap<>();
        for (String event : SOUND_EVENTS) {
            s.put(event, SoundSpec.read(c.getConfigurationSection("sounds." + event), SoundSpec.SILENT, "sounds." + event, warn));
        }
        sounds = Map.copyOf(s);
        outDelayTicks = clamp(c.getInt("sounds.out-delay-ticks", 16), 1, 200);

        popupEnabled = c.getBoolean("popup.enabled", true);
        String side = c.getString("popup.side", "right").trim().toLowerCase(Locale.ROOT);
        if (!side.equals("right") && !side.equals("left")) warn.accept("popup.side: unknown value '" + side + "', using right");
        popupLeft = side.equals("left");
        popupScale = Math.max(0.5, Math.min(2.0, c.getDouble("popup.scale", 1.0)));
        popupMarginX = clamp(c.getInt("popup.margin-x", 4), 0, 1000);
        popupMarginTop = clamp(c.getInt("popup.margin-top", 4), 0, 1000);
        popupPlainFallback = c.getBoolean("popup.plain-fallback", true);
        popupMessagesInChat = c.getBoolean("popup.messages-in-chat", false);

        postEnabled = c.getBoolean("post-effects.enabled", true);
        postTransmission = key(c.getString("post-effects.transmission", ""), "post-effects.transmission", warn);
        postActive = key(c.getString("post-effects.active", ""), "post-effects.active", warn);
        postTransmissionTicks = clamp(c.getInt("post-effects.transmission-ticks", 10), 1, 200);

        String delivery = c.getString("pack.delivery", "auto").toLowerCase(Locale.ROOT);
        if (!List.of("auto", "same-port", "http", "url", "none").contains(delivery)) {
            warn.accept("pack.delivery: unknown value '" + delivery + "', using auto");
            delivery = "auto";
        }
        packDelivery = delivery;
        packUrl = c.getString("pack.url", "");
        String sha1 = c.getString("pack.sha1", "").toLowerCase(Locale.ROOT);
        if (!sha1.isEmpty() && !sha1.matches("[0-9a-f]{40}")) {
            warn.accept("pack.sha1: not a SHA-1 (40 hex characters), ignoring it");
            sha1 = "";
        }
        packSha1 = sha1;
        packRequired = c.getBoolean("pack.required", false);
        packPrompt = c.getString("pack.prompt", "");
        packPublicHost = c.getString("pack.public-host", "");
        packHttpBind = c.getString("pack.http.bind", "0.0.0.0");
        packHttpPort = clamp(c.getInt("pack.http.port", 8129), 1, 65535);
        packSourceFolder = c.getString("pack.source-folder", "");
        packTextShader = c.getBoolean("pack.text-shader.enabled", true);
        packSuperbVehiclesZip = c.getString("pack.text-shader.superbvehicles-pack", "SuperbVehicles/pack/SuperbVehicles-hud.zip");
        packCctvZip = c.getString("pack.text-shader.cctv-pack", "CCTV/cctv-pack.zip");
        packSendDelayTicks = clamp(c.getInt("pack.send-delay-ticks", 40), 0, 600);

        voiceEnabled = c.getBoolean("voice.enabled", false);
    }

    private boolean validRecipe(Consumer<String> warn) {
        List<String> problems = new ArrayList<>();
        if (recipeShape.isEmpty() || recipeShape.size() > 3) problems.add("the shape needs 1-3 rows");
        for (String row : recipeShape) {
            if (row.isEmpty() || row.length() > 3 || row.length() != recipeShape.get(0).length()) problems.add("rows must be 1-3 characters and equally long");
            for (char ch : row.toCharArray()) if (ch != ' ' && !recipeIngredients.containsKey(ch)) problems.add("no ingredient for '" + ch + "'");
        }
        if (recipeIngredients.isEmpty()) problems.add("no ingredients");
        if (problems.isEmpty()) return true;
        warn.accept("item.recipe: " + problems.get(0) + "; the recipe is disabled");
        return false;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, E def, String path, Consumer<String> warn) {
        try {
            return Enum.valueOf(type, String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn.accept(path + ": unknown value '" + raw + "', using " + def.name().toLowerCase(Locale.ROOT));
            return def;
        }
    }

    public static Material material(String raw, Material def, String path, Consumer<String> warn) {
        Material m = raw == null ? null : Material.matchMaterial(raw.trim());
        if (m != null && m.isItem() && !m.isAir()) return m;
        warn.accept(path + ": '" + raw + "' is not an item" + (def == null ? "" : ", using " + def.name()));
        return def;
    }

    /** A namespaced id, or "" when empty/invalid. */
    private static String key(String raw, String path, Consumer<String> warn) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty() || s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return s;
        warn.accept(path + ": '" + raw + "' is not a namespaced id (like radio:gui), ignoring it");
        return "";
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
