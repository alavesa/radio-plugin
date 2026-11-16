package fi.alavesa.radio.integration;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Everything that touches the LuckPerms API. This class is only loaded when LuckPerms is installed (see
 * RadioPlugin), so the plugin runs without it.
 *
 * <ul>
 *   <li>Context {@code radio-channel=<id>}: the channel of the player's powered radio, so permissions can be scoped
 *       ("/lp user X permission set some.node true radio-channel=security").</li>
 *   <li>{@code UserDataRecalculateEvent}: when a player's permissions change, their open radio GUI is refreshed.</li>
 *   <li>Prefix and suffix for radio messages.</li>
 * </ul>
 */
public final class LuckPermsHook {

    public static final String CONTEXT = "radio-channel";

    private final LuckPerms api;
    private final ContextCalculator<Player> calculator;
    private final EventSubscription<UserDataRecalculateEvent> subscription;

    /**
     * @param tuned         thread-safe: player -> channel id of their powered radio, or null
     * @param channelIds    the known channel ids (for LuckPerms' tab completion of context values)
     * @param onRecalculate called on the main thread with the player whose permissions changed
     */
    public LuckPermsHook(Plugin plugin, Function<UUID, String> tuned, java.util.function.Supplier<Iterable<String>> channelIds,
                         Consumer<Player> onRecalculate) {
        api = Bukkit.getServicesManager().load(LuckPerms.class);
        if (api == null) throw new IllegalStateException("LuckPerms is installed but its API is not registered");
        calculator = new ContextCalculator<>() {
            @Override
            public void calculate(Player target, ContextConsumer consumer) {
                // may be called from any thread: only the tracker's concurrent snapshot is read
                String channel = tuned.apply(target.getUniqueId());
                if (channel != null) consumer.accept(CONTEXT, channel);
            }

            @Override
            public ContextSet estimatePotentialContexts() {
                ImmutableContextSet.Builder b = ImmutableContextSet.builder();
                for (String id : channelIds.get()) b.add(CONTEXT, id);
                return b.build();
            }
        };
        api.getContextManager().registerCalculator(calculator);
        // fired on LuckPerms' own threads
        subscription = api.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, e -> {
            UUID id = e.getUser().getUniqueId();
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) onRecalculate.accept(p);
            });
        });
    }

    /** The player's tuned channel changed: LuckPerms must re-evaluate their contexts. */
    public void contextChanged(Player p) {
        api.getContextManager().signalContextUpdate(p);
    }

    /** {prefix, suffix}, each "" when unset. */
    public String[] meta(Player p) {
        CachedMetaData meta = api.getPlayerAdapter(Player.class).getMetaData(p);
        String prefix = meta.getPrefix(), suffix = meta.getSuffix();
        return new String[]{prefix == null ? "" : prefix, suffix == null ? "" : suffix};
    }

    public void close() {
        subscription.close();
        api.getContextManager().unregisterCalculator(calculator);
    }
}
