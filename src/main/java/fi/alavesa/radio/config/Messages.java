package fi.alavesa.radio.config;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * messages.yml: every text the plugin shows, as MiniMessage. Keys missing from the file on disk come from the copy
 * inside the jar (the file's defaults), so an old messages.yml keeps working after an update.
 */
public final class Messages {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    private final FileConfiguration file;

    public Messages(FileConfiguration file) {
        this.file = file;
    }

    public String raw(String key) {
        String s = file.getString(key);
        return s == null ? "" : s;
    }

    public Component get(String key, TagResolver... tags) {
        return MM.deserialize(raw(key), tags);
    }

    /** An item name or lore line: not italic unless the message asks for it. */
    public Component item(String key, TagResolver... tags) {
        return plainStyle(get(key, tags));
    }

    public List<Component> itemLines(String key, TagResolver... tags) {
        List<Component> out = new ArrayList<>();
        for (String line : file.getStringList(key)) out.add(plainStyle(MM.deserialize(line, tags)));
        return out;
    }

    public static Component plainStyle(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Sends the message with the prefix; nothing when the message is "". */
    public void send(Audience to, String key, TagResolver... tags) {
        String raw = raw(key);
        if (raw.isEmpty()) return;
        to.sendMessage(MM.deserialize(raw("prefix")).append(MM.deserialize(raw, tags)));
    }
}
