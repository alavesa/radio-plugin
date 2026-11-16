package fi.alavesa.radio.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fi.alavesa.radio.RadioPlugin;
import fi.alavesa.radio.channels.Channel;
import fi.alavesa.radio.channels.ChannelRegistry;
import fi.alavesa.radio.config.Messages;
import fi.alavesa.radio.config.RadioConfig;
import fi.alavesa.radio.item.RadioLocator;
import fi.alavesa.radio.permissions.Perms;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * /radio, registered with Brigadier through Paper's lifecycle API:
 *
 * <pre>
 * /radio                                   open the GUI
 * /radio talk                              open the message dialog
 * /radio say &lt;message&gt;                     transmit once
 * /radio chat                              toggle radio chat (input mode chat/dialog)
 * /radio power [on|off]                    switch the radio
 * /radio give &lt;players&gt; [amount]           radio.give
 * /radio channel list                      radio.admin
 * /radio channel create &lt;id&gt; [name]        radio.admin
 * /radio channel delete &lt;id&gt;               radio.admin
 * /radio channel rename &lt;id&gt; &lt;name&gt;        radio.admin
 * /radio channel color &lt;id&gt; &lt;color&gt;        radio.admin
 * /radio channel frequency &lt;id&gt; &lt;label&gt;    radio.admin
 * /radio pack                              resend the resource pack to yourself, show its status
 * /radio reload                            radio.reload
 * </pre>
 */
public final class RadioCommand {

    private final RadioPlugin plugin;

    public RadioCommand(RadioPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        SuggestionProvider<CommandSourceStack> channelIds = (ctx, b) -> {
            String typed = b.getRemainingLowerCase();
            for (String id : plugin.channels().ids()) if (id.startsWith(typed)) b.suggest(id);
            return b.buildFuture();
        };
        SuggestionProvider<CommandSourceStack> colors = (ctx, b) -> {
            String typed = b.getRemainingLowerCase();
            for (String name : NamedTextColor.NAMES.keys()) if (name.startsWith(typed)) b.suggest(name);
            return b.buildFuture();
        };

        LiteralArgumentBuilder<CommandSourceStack> channel = Commands.literal("channel").requires(has(Perms.ADMIN))
                .then(Commands.literal("list").executes(this::channelList))
                .then(Commands.literal("create").then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> channelCreate(ctx, null))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> channelCreate(ctx, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("delete").then(Commands.argument("id", StringArgumentType.word()).suggests(channelIds)
                        .executes(this::channelDelete)))
                .then(Commands.literal("rename").then(Commands.argument("id", StringArgumentType.word()).suggests(channelIds)
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::channelRename))))
                .then(Commands.literal("color").then(Commands.argument("id", StringArgumentType.word()).suggests(channelIds)
                        .then(Commands.argument("color", StringArgumentType.greedyString()).suggests(colors).executes(this::channelColor))))
                .then(Commands.literal("frequency").then(Commands.argument("id", StringArgumentType.word()).suggests(channelIds)
                        .then(Commands.argument("label", StringArgumentType.greedyString()).executes(this::channelFrequency))));

        return Commands.literal("radio")
                .requires(src -> src.getSender().hasPermission(Perms.USE) || src.getSender().hasPermission(Perms.GIVE)
                        || src.getSender().hasPermission(Perms.RELOAD) || src.getSender().hasPermission(Perms.ADMIN))
                .executes(ctx -> player(ctx, p -> plugin.menus().open(p)))
                .then(Commands.literal("talk").requires(has(Perms.USE)).executes(ctx -> player(ctx, p -> plugin.dialog().open(p))))
                .then(Commands.literal("say").requires(has(Perms.USE))
                        .then(Commands.argument("message", StringArgumentType.greedyString()).executes(ctx ->
                                player(ctx, p -> plugin.transmitter().transmit(p, null, StringArgumentType.getString(ctx, "message"))))))
                .then(Commands.literal("chat").requires(has(Perms.USE)).executes(ctx -> player(ctx, this::toggleChat)))
                .then(Commands.literal("power").requires(has(Perms.USE))
                        .executes(ctx -> player(ctx, p -> power(p, null)))
                        .then(Commands.literal("on").executes(ctx -> player(ctx, p -> power(p, true))))
                        .then(Commands.literal("off").executes(ctx -> player(ctx, p -> power(p, false)))))
                .then(Commands.literal("give").requires(has(Perms.GIVE))
                        .then(Commands.argument("players", ArgumentTypes.players())
                                .executes(ctx -> give(ctx, 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
                .then(channel)
                .then(Commands.literal("pack").requires(has(Perms.USE)).executes(ctx -> player(ctx, this::pack)))
                .then(Commands.literal("reload").requires(has(Perms.RELOAD)).executes(this::reload))
                .build();
    }

    private static Predicate<CommandSourceStack> has(String permission) {
        return src -> src.getSender().hasPermission(permission);
    }

    private interface PlayerAction {
        void run(Player p);
    }

    /** Runs the action for the executing player (so /execute as works), or tells the console it cannot. */
    private int player(CommandContext<CommandSourceStack> ctx, PlayerAction action) {
        if (ctx.getSource().getExecutor() instanceof Player p) {
            action.run(p);
            return 1;
        }
        plugin.messages().send(ctx.getSource().getSender(), "error.players-only");
        return 0;
    }

    private void toggleChat(Player p) {
        boolean chat = plugin.players().mode(p, plugin.config()) == RadioConfig.Mode.CHAT;
        plugin.players().mode(p, chat ? RadioConfig.Mode.DIALOG : RadioConfig.Mode.CHAT);
        plugin.messages().send(p, chat ? "mode.chat-off" : "mode.chat-on");
        plugin.menus().refresh(p);
    }

    private void power(Player p, @Nullable Boolean on) {
        RadioLocator.Found radio = plugin.locator().active(p);
        if (radio == null) {
            plugin.messages().send(p, "error.no-radio", Placeholder.component("rule",
                    plugin.messages().get("rule." + plugin.config().carry.name().toLowerCase(Locale.ROOT))));
            return;
        }
        plugin.togglePower(p, radio, on);
    }

    private void pack(Player p) {
        var status = plugin.pack().status(p);
        plugin.messages().send(p, "pack.status",
                Placeholder.unparsed("status", status == null ? "no answer yet" : status.name().toLowerCase(Locale.ROOT).replace('_', ' ')),
                Placeholder.unparsed("delivery", plugin.pack().delivery()));
        if (!plugin.pack().loaded(p)) {
            plugin.messages().send(p, "pack.resent");
            plugin.pack().send(p);
        }
    }

    private int give(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        List<Player> targets = ctx.getArgument("players", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (targets.isEmpty()) {
            plugin.messages().send(sender, "error.no-players");
            return 0;
        }
        for (Player target : targets) {
            for (int i = 0; i < amount; i++) {
                // every radio is its own item (own id, stack size 1); what does not fit is dropped at the player's feet
                for (ItemStack left : target.getInventory().addItem(plugin.item().create()).values()) {
                    target.getWorld().dropItem(target.getLocation(), left);
                }
            }
            plugin.messages().send(target, "give.received");
            plugin.messages().send(sender, "give.given", Placeholder.unparsed("amount", String.valueOf(amount)),
                    Placeholder.unparsed("player", target.getName()));
        }
        return targets.size();
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        List<String> problems = plugin.reload();
        Messages msg = plugin.messages();
        msg.send(ctx.getSource().getSender(), "reload.done",
                Placeholder.unparsed("channels", String.valueOf(plugin.channels().all().size())),
                Placeholder.component("problems", problems.isEmpty() ? Component.empty()
                        : msg.get("reload.problems", Placeholder.unparsed("count", String.valueOf(problems.size())))));
        return 1;
    }

    // ------------------------------------------------------------------ /radio channel

    private int channelList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Messages msg = plugin.messages();
        msg.send(sender, "channel.list-header", Placeholder.unparsed("count", String.valueOf(plugin.channels().all().size())));
        for (Channel c : plugin.channels().all()) {
            sender.sendMessage(msg.get("channel.list-entry", Placeholder.component("channel", c.displayName()),
                    Placeholder.unparsed("id", c.id()), Placeholder.unparsed("frequency", c.frequency().isEmpty() ? "-" : c.frequency()),
                    Placeholder.unparsed("listeners", String.valueOf(plugin.tracker().listenerCount(c.id())))));
        }
        return 1;
    }

    private int channelCreate(CommandContext<CommandSourceStack> ctx, @Nullable String name) {
        CommandSender sender = ctx.getSource().getSender();
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        if (!ChannelRegistry.ID.matcher(id).matches()) {
            plugin.messages().send(sender, "error.invalid-id");
            return 0;
        }
        if (plugin.channels().get(id) != null) {
            plugin.messages().send(sender, "error.channel-exists", Placeholder.unparsed("id", id));
            return 0;
        }
        Channel c = plugin.channels().create(id, name == null ? id : name);
        plugin.channelsChanged();
        plugin.messages().send(sender, "channel.created", Placeholder.component("channel", c.displayName()), Placeholder.unparsed("id", id));
        return 1;
    }

    /** The channel named by the "id" argument, or null after telling the sender it does not exist. */
    private @Nullable Channel channel(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        Channel c = plugin.channels().get(id);
        if (c == null) plugin.messages().send(ctx.getSource().getSender(), "error.unknown-channel", Placeholder.unparsed("id", id));
        return c;
    }

    private int channelDelete(CommandContext<CommandSourceStack> ctx) {
        Channel c = channel(ctx);
        if (c == null) return 0;
        if (c.isGlobal()) {
            plugin.messages().send(ctx.getSource().getSender(), "error.global-protected");
            return 0;
        }
        plugin.channels().delete(c.id());
        plugin.channelsChanged();
        plugin.messages().send(ctx.getSource().getSender(), "channel.deleted", Placeholder.unparsed("id", c.id()));
        return 1;
    }

    private int channelRename(CommandContext<CommandSourceStack> ctx) {
        Channel c = channel(ctx);
        if (c == null) return 0;
        Channel renamed = c.withName(StringArgumentType.getString(ctx, "name"));
        plugin.channels().replace(renamed);
        plugin.channelsChanged();
        plugin.messages().send(ctx.getSource().getSender(), "channel.renamed", Placeholder.unparsed("id", c.id()),
                Placeholder.component("channel", renamed.displayName()));
        return 1;
    }

    private int channelColor(CommandContext<CommandSourceStack> ctx) {
        Channel c = channel(ctx);
        if (c == null) return 0;
        String raw = StringArgumentType.getString(ctx, "color");
        TextColor color = ChannelRegistry.parseColor(raw);
        if (color == null) {
            plugin.messages().send(ctx.getSource().getSender(), "error.invalid-color", Placeholder.unparsed("color", raw));
            return 0;
        }
        Channel recolored = c.withColor(color);
        plugin.channels().replace(recolored);
        plugin.channelsChanged();
        plugin.messages().send(ctx.getSource().getSender(), "channel.recolored", Placeholder.component("channel", recolored.displayName()));
        return 1;
    }

    private int channelFrequency(CommandContext<CommandSourceStack> ctx) {
        Channel c = channel(ctx);
        if (c == null) return 0;
        Channel changed = c.withFrequency(StringArgumentType.getString(ctx, "label").strip());
        plugin.channels().replace(changed);
        plugin.channelsChanged();
        plugin.messages().send(ctx.getSource().getSender(), "channel.frequency-set", Placeholder.component("channel", changed.displayName()),
                Placeholder.unparsed("frequency", changed.frequency()));
        return 1;
    }
}
