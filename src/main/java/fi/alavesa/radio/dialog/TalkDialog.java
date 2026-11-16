package fi.alavesa.radio.dialog;

import fi.alavesa.radio.RadioPlugin;
import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.item.RadioLocator;
import fi.alavesa.radio.permissions.Perms;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The transmission dialog (Paper Dialog API): a text box, a selector with the channels the player may speak on, and
 * Send / Cancel. Opened from the radio GUI and by /radio talk.
 */
public final class TalkDialog {

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1)
            .lifetime(Duration.ofMinutes(10)).build();

    private final RadioPlugin plugin;

    public TalkDialog(RadioPlugin plugin) {
        this.plugin = plugin;
    }

    /** Opens the dialog, or tells the player why not (no radio, radio off, no channel to speak on). */
    public void open(Player p) {
        Messages msg = plugin.messages();
        if (!p.hasPermission(Perms.USE)) {
            msg.send(p, "error.no-permission");
            return;
        }
        RadioLocator.Found radio = plugin.locator().active(p);
        if (radio == null) {
            msg.send(p, "error.no-radio", Placeholder.component("rule",
                    msg.get("rule." + plugin.config().carry.name().toLowerCase(Locale.ROOT))));
            return;
        }
        if (!radio.state().power()) {
            msg.send(p, "error.radio-off");
            return;
        }
        List<Channel> speakable = Perms.speakable(p, plugin.channels());
        if (speakable.isEmpty()) {
            msg.send(p, "error.no-channel");
            return;
        }
        String tuned = radio.state().channel();
        boolean tunedSpeakable = speakable.stream().anyMatch(c -> c.id().equals(tuned));
        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
        for (int i = 0; i < speakable.size(); i++) {
            Channel c = speakable.get(i);
            boolean initial = tunedSpeakable ? c.id().equals(tuned) : i == 0;
            options.add(SingleOptionDialogInput.OptionEntry.create(c.id(), c.displayName(), initial));
        }
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(msg.get("dialog.title"))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(msg.get("dialog.body"), 300)))
                        .inputs(List.of(
                                DialogInput.text("message", msg.get("dialog.message-label")).width(300)
                                        .maxLength(plugin.config().maxLength).build(),
                                DialogInput.singleOption("channel", msg.get("dialog.channel-label"), options).width(300).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(msg.get("dialog.send"), null, 120, DialogAction.customClick((view, who) -> {
                            String text = view.getText("message");
                            String channel = view.getText("channel");
                            // the callback thread is not specified by the API: always continue on the main thread
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (p.isOnline() && text != null) plugin.transmitter().transmit(p, channel, text);
                            });
                        }, ONCE)),
                        ActionButton.create(msg.get("dialog.cancel"), null, 120, null))));
        p.showDialog(dialog);
    }
}
