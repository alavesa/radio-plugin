package fi.alavesa.radio.integration;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import fi.alavesa.radio.item.RadioTracker;
import java.util.UUID;
import org.bukkit.Bukkit;

/**
 * EXPERIMENTAL, optional module (config voice.enabled, needs the Simple Voice Chat plugin): while a player holds a
 * powered radio in the main hand, their microphone audio is also sent, non-positional, to everyone listening on that
 * channel. Proximity voice keeps working as usual, so a listener standing next to the speaker hears both.
 *
 * <p>Voice packets arrive on Simple Voice Chat's own thread; only the tracker's thread-safe snapshot is read here.
 * Only loaded when Simple Voice Chat is installed. Not tested against a live voice chat server.
 */
public final class VoiceChatModule implements VoicechatPlugin {

    private final RadioTracker tracker;
    private volatile boolean enabled = true;

    private VoiceChatModule(RadioTracker tracker) {
        this.tracker = tracker;
    }

    /** @return the module, or null when Simple Voice Chat's service is not available */
    public static VoiceChatModule register(RadioTracker tracker) {
        BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) return null;
        VoiceChatModule module = new VoiceChatModule(tracker);
        service.registerPlugin(module);
        return module;
    }

    /** Simple Voice Chat has no unregister: the module goes quiet instead. */
    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String getPluginId() {
        return "radio";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent e) {
        if (!enabled || e.getSenderConnection() == null) return;
        UUID sender = e.getSenderConnection().getPlayer().getUuid();
        String channel = tracker.voiceChannel(sender);
        if (channel == null) return;
        VoicechatServerApi api = e.getVoicechat();
        for (UUID listener : tracker.listeners(channel)) {
            if (listener.equals(sender)) continue;
            VoicechatConnection to = api.getConnectionOf(listener);
            if (to == null || !to.isConnected() || to.isDisabled()) continue;
            api.sendStaticSoundPacketTo(to, e.getPacket().staticSoundPacketBuilder().build());
        }
    }
}
