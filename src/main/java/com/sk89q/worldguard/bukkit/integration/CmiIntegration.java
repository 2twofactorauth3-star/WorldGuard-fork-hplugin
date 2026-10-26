package com.sk89q.worldguard.bukkit.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitPlayer;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.listener.PlayerMoveListener;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.session.AbstractSessionManager;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.session.handler.FlagValueChangeHandler;
import com.sk89q.worldguard.session.handler.GameModeFlag;
import com.sk89q.worldguard.session.handler.Handler;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CmiIntegration implements AutoCloseable {

    private final Plugin owner;
    private final Logger logger;
    private final StateFlag godFlag;
    private final EnumMap<Action, StateFlag> actionFlags;
    private final Function<Plugin, GodApi> godFactory;
    private final Function<Player, LocalPlayer> playerWrapper;
    private final IntegrationPlayerRefresh<Binding> playerRefresh;
    private final BiConsumer<Player, Action> deniedMessage;
    private final Handler.Factory<GodHandler> factory = new Handler.Factory<>() {
        @Override
        public GodHandler create(Session session) {
            return new GodHandler(session, CmiIntegration.this);
        }
    };
    private volatile Binding binding;
    private AbstractSessionManager sessionManager;
    private PluginDependencyListener dependencyListener;

    private CmiIntegration(Plugin owner, Logger logger, StateFlag godFlag, EnumMap<Action, StateFlag> actionFlags,
                           Function<Plugin, GodApi> godFactory, Function<Player, LocalPlayer> playerWrapper,
                           BiConsumer<Player, Action> deniedMessage) {
        this.owner = owner;
        this.logger = logger;
        this.godFlag = godFlag;
        this.actionFlags = actionFlags;
        this.godFactory = godFactory;
        this.playerWrapper = playerWrapper;
        playerRefresh = new IntegrationPlayerRefresh<>(owner, playerWrapper, () -> binding, this::refreshSession);
        this.deniedMessage = deniedMessage;
    }

    @Nullable
    public static CmiIntegration prepare(WorldGuardPlugin owner, FlagRegistry registry, Logger logger) {
        return prepare(owner, registry, logger, dependency -> CmiGodApi.create(dependency, owner.getServer().getConsoleSender()),
                owner::wrapPlayer, (player, action) -> owner.getMessages().send(player, "@wg:" + action.messageKey + "@"));
    }

    @Nullable
    static CmiIntegration prepare(Plugin owner, FlagRegistry registry, Logger logger, Function<Plugin, GodApi> godFactory,
                                   Function<Player, LocalPlayer> playerWrapper, BiConsumer<Player, Action> deniedMessage) {
        StateFlag god = stateFlag(registry, "cmi-god", false, logger);
        var flags = new EnumMap<Action, StateFlag>(Action.class);
        for (Action action : Action.values()) {
            StateFlag flag = stateFlag(registry, action.flagName, true, logger);
            if (flag == null) return null;
            flags.put(action, flag);
        }
        return god == null ? null : new CmiIntegration(owner, logger, god, flags, godFactory, playerWrapper, deniedMessage);
    }

    @Nullable
    private static StateFlag stateFlag(FlagRegistry registry, String name, boolean allowed, Logger logger) {
        Flag<?> existing = registry.get(name);
        if (existing instanceof StateFlag flag) return flag;
        if (existing != null) {
            logger.warning("@wglog:cmiIntegrationFlagConflict@" + name);
            return null;
        }
        var flag = new StateFlag(name, allowed);
        registry.register(flag);
        return flag;
    }

    public void start(AbstractSessionManager manager) {
        if (sessionManager != manager) {
            manager.registerTrackedHandler(factory, GameModeFlag.FACTORY);
            sessionManager = manager;
        }
        if (dependencyListener != null) return;
        dependencyListener = new PluginDependencyListener(owner, "CMI", this::connect, this::disconnect);
        dependencyListener.start();
    }

    boolean connect(Plugin dependency) {
        if (!"CMI".equals(dependency.getName()) || !dependency.isEnabled()) return false;
        Binding current = binding;
        if (current != null && current.plugin == dependency) return true;
        Binding connected = new Binding(dependency);
        try {
            connected.god = godFactory.apply(dependency);
            if (connected.god == null) throw new IllegalStateException("CMI god API is not available");
            CmiEvents.register(owner, connected.listener,
                    (action, event, player, target) -> onAction(connected, action, event, player, target));
        } catch (RuntimeException | LinkageError error) {
            HandlerList.unregisterAll(connected.listener);
            logger.log(Level.WARNING, "@wglog:cmiIntegrationIncompatible@", error);
            return false;
        }
        if (!dependency.isEnabled()) {
            HandlerList.unregisterAll(connected.listener);
            return false;
        }
        binding = connected;
        PlayerMoveListener.setMovementFlags(this, Set.of(godFlag));
        refreshPlayers(connected);
        logger.info("@wglog:cmiIntegrationEnabled@");
        return true;
    }

    private void onAction(Binding connected, Action action, Cancellable event, Player player, @Nullable ArmorStand target) {
        if (!active(connected) || event.isCancelled()) return;
        try {
            if (player == null) return;
            LocalPlayer local = playerWrapper.apply(player);
            Location location = actionLocation(local, player, target);
            if (sessionManager.hasBypass(local, (World) location.getExtent())) return;
            var query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            if (query.getApplicableRegions(location).queryState(local, actionFlags.get(action)) != State.DENY) return;
            event.setCancelled(true);
            deniedMessage.accept(player, action);
        } catch (RuntimeException | LinkageError error) {
            event.setCancelled(true);
            if (connected.failedActions.add(action)) {
                logger.log(Level.WARNING, "@wglog:cmiIntegrationActionFailed@" + action.flagName, error);
            }
        }
    }

    private Location actionLocation(LocalPlayer local, Player player, @Nullable ArmorStand stand) {
        if (stand == null) return local.getLocation();
        org.bukkit.Location target = stand.getLocation();
        World world = target.getWorld() == player.getWorld() ? local.getWorld() : BukkitAdapter.adapt(target.getWorld());
        return new Location(world, target.getX(), target.getY(), target.getZ());
    }

    void disconnect(Plugin dependency) {
        Binding current = binding;
        if (current == null || current.plugin != dependency) return;
        binding = null;
        HandlerList.unregisterAll(current.listener);
        PlayerMoveListener.setMovementFlags(this, Set.of());
        refreshPlayers(null);
        logger.info("@wglog:cmiIntegrationDisabled@");
    }

    private boolean active(Binding expected) {
        return binding == expected && expected.plugin.isEnabled();
    }

    private void refreshPlayers(@Nullable Binding expected) {
        playerRefresh.refresh(sessionManager, expected);
    }

    void refreshSession(Session session, LocalPlayer player, Location location, ApplicableRegionSet regions) {
        GodHandler handler = session.getHandler(GodHandler.class);
        if (handler != null) handler.initialize(player, location, regions);
    }

    @Override
    public void close() {
        if (dependencyListener != null) {
            dependencyListener.close();
            dependencyListener = null;
        }
        Binding current = binding;
        binding = null;
        if (current != null) HandlerList.unregisterAll(current.listener);
        sessionManager = null;
        PlayerMoveListener.setMovementFlags(this, Set.of());
    }

    public enum Action {
        BACKPACK("cmi-backpack", "cmiBackpackDenied"),
        PORTAL("cmi-portal", "cmiPortalDenied"),
        ARMORSTAND_EDIT("cmi-armorstand-edit", "cmiArmorstandEditDenied");

        public final String flagName;
        public final String messageKey;

        Action(String flagName, String messageKey) {
            this.flagName = flagName;
            this.messageKey = messageKey;
        }
    }

    interface GodApi {
        GodState capture(Player player);
        void apply(Player player, boolean value);
        void restore(Player player, GodState state);
    }

    static final class GodState {
        public final boolean invulnerable;
        public final long temporaryGod;

        GodState(boolean invulnerable, long temporaryGod) {
            this.invulnerable = invulnerable;
            this.temporaryGod = temporaryGod;
        }
    }

    private static final class Binding {
        public final Plugin plugin;
        public final Listener listener = new Listener() { };
        public final Set<UUID> failedPlayers = ConcurrentHashMap.newKeySet();
        public final Set<Action> failedActions = ConcurrentHashMap.newKeySet();
        private GodApi god;

        private Binding(Plugin plugin) {
            this.plugin = plugin;
        }
    }

    private static final class GodHandler extends FlagValueChangeHandler<State> {
        private final CmiIntegration integration;
        private Binding binding;
        private GodState original;

        private GodHandler(Session session, CmiIntegration integration) {
            super(session, integration.godFlag);
            this.integration = integration;
        }

        private void update(LocalPlayer local, @Nullable State state, World world) {
            Binding current = integration.binding;
            if (binding != current) {
                binding = current;
                original = null;
            }
            if (current == null || !integration.active(current)) return;
            Player player = local instanceof BukkitPlayer bukkit ? bukkit.getPlayer()
                    : integration.owner.getServer().getPlayer(local.getUniqueId());
            if (player == null) return;
            try {
                if (state == null || getSession().getManager().hasBypass(local, world)) {
                    if (original != null) {
                        current.god.restore(player, original);
                        original = null;
                    }
                } else {
                    if (original == null) original = current.god.capture(player);
                    current.god.apply(player, state == State.ALLOW);
                }
            } catch (RuntimeException | LinkageError error) {
                if (current.failedPlayers.add(local.getUniqueId())) {
                    integration.logger.log(Level.WARNING, "@wglog:cmiIntegrationPlayerFailed@" + local.getName(), error);
                }
            }
        }

        @Override
        protected void onInitialValue(LocalPlayer player, ApplicableRegionSet set, State value) {
            update(player, value, player.getWorld());
        }

        @Override
        protected boolean onSetValue(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet,
                                     State currentValue, State lastValue, MoveType moveType) {
            update(player, currentValue, (World) to.getExtent());
            return true;
        }

        @Override
        protected boolean onAbsentValue(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet,
                                        State lastValue, MoveType moveType) {
            update(player, null, (World) to.getExtent());
            return true;
        }
    }
}
