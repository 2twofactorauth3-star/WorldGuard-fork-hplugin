package com.sk89q.worldguard.bukkit.integration;

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
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class VanishIntegration implements AutoCloseable {

    private final Plugin owner;
    private final Type type;
    private final StateFlag flag;
    private final Logger logger;
    private final Function<Plugin, Provider> providerFactory;
    private final Function<Player, LocalPlayer> playerWrapper;
    private final Class<? extends VanishHandler> handlerType;
    private final Handler.Factory<VanishHandler> factory;
    private volatile Binding binding;
    private AbstractSessionManager sessionManager;
    private PluginDependencyListener dependencyListener;

    private VanishIntegration(Plugin owner, Type type, StateFlag flag, Logger logger,
                             Function<Plugin, Provider> providerFactory, Function<Player, LocalPlayer> playerWrapper) {
        this.owner = owner;
        this.type = type;
        this.flag = flag;
        this.logger = logger;
        this.providerFactory = providerFactory;
        this.playerWrapper = playerWrapper;
        handlerType = switch (type) {
            case PREMIUM -> PremiumVanishHandler.class;
            case SUPER -> SuperVanishHandler.class;
            case CMI -> CmiVanishHandler.class;
        };
        factory = new Handler.Factory<>() {
            @Override
            public VanishHandler create(Session session) {
                return switch (type) {
                    case PREMIUM -> new PremiumVanishHandler(session, VanishIntegration.this);
                    case SUPER -> new SuperVanishHandler(session, VanishIntegration.this);
                    case CMI -> new CmiVanishHandler(session, VanishIntegration.this);
                };
            }
        };
    }

    @Nullable
    public static VanishIntegration prepare(Plugin owner, Type type, FlagRegistry registry, Logger logger) {
        return prepare(owner, type, registry, logger, type == Type.CMI ? CmiVanishApi::create : VanishApi::create,
                player -> WorldGuardPlugin.inst().wrapPlayer(player));
    }

    @Nullable
    static VanishIntegration prepare(Plugin owner, Type type, FlagRegistry registry, Logger logger,
                                     Function<Plugin, Provider> providerFactory,
                                     Function<Player, LocalPlayer> playerWrapper) {
        Flag<?> existing = registry.get(type.flagName);
        if (existing != null && !(existing instanceof StateFlag)) {
            logger.warning("@wglog:vanishIntegrationFlagConflict@" + type.flagName);
            return null;
        }
        StateFlag flag = existing instanceof StateFlag stateFlag ? stateFlag : new StateFlag(type.flagName, false);
        if (existing == null) registry.register(flag);
        return new VanishIntegration(owner, type, flag, logger, providerFactory, playerWrapper);
    }

    public void start(AbstractSessionManager manager) {
        if (sessionManager != manager) {
            manager.registerTrackedHandler(factory, GameModeFlag.FACTORY);
            sessionManager = manager;
        }
        if (dependencyListener != null) return;
        dependencyListener = new PluginDependencyListener(owner, type.pluginName, this::connect, this::disconnect);
        dependencyListener.start();
    }

    boolean connect(Plugin dependency) {
        if (!type.pluginName.equals(dependency.getName()) || !dependency.isEnabled()) return false;
        Binding current = binding;
        if (current != null && current.plugin == dependency) return true;
        Provider provider;
        try {
            provider = providerFactory.apply(dependency);
        } catch (RuntimeException | LinkageError error) {
            logger.log(Level.WARNING, "@wglog:vanishIntegrationIncompatible@" + type.pluginName, error);
            return false;
        }
        if (provider == null) {
            logger.warning("@wglog:vanishIntegrationIncompatible@" + type.pluginName);
            return false;
        }
        if (!dependency.isEnabled()) return false;
        Binding connected = new Binding(dependency, provider);
        binding = connected;
        PlayerMoveListener.setMovementFlags(this, Set.of(flag));
        refreshPlayers(connected);
        logger.info("@wglog:vanishIntegrationEnabled@" + type.pluginName);
        return true;
    }

    void disconnect(Plugin dependency) {
        Binding current = binding;
        if (current == null || current.plugin != dependency) return;
        binding = null;
        PlayerMoveListener.setMovementFlags(this, Set.of());
        refreshPlayers(null);
        logger.info("@wglog:vanishIntegrationDisabled@" + type.pluginName);
    }

    private void refreshPlayers(@Nullable Binding expected) {
        AbstractSessionManager manager = sessionManager;
        if (manager == null || !owner.isEnabled()) return;
        for (Player player : owner.getServer().getOnlinePlayers()) {
            Runnable refresh = () -> refreshPlayer(player, manager, expected);
            if (owner instanceof WorldGuardPlugin plugin && plugin.isFolia()) {
                player.getScheduler().run(owner, ignored -> refresh.run(), null);
            } else {
                owner.getServer().getScheduler().runTask(owner, refresh);
            }
        }
    }

    private void refreshPlayer(Player player, AbstractSessionManager manager, @Nullable Binding expected) {
        if (binding != expected || !owner.isEnabled() || !player.isOnline()) return;
        LocalPlayer localPlayer = playerWrapper.apply(player);
        Session session = expected == null ? manager.getIfPresent(localPlayer) : manager.get(localPlayer);
        if (session == null) return;
        Location location = localPlayer.getLocation();
        if (expected != null) session.testMoveTo(localPlayer, location, MoveType.OTHER_NON_CANCELLABLE);
        ApplicableRegionSet regions = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().createQuery().getApplicableRegions(location);
        refreshSession(session, localPlayer, location, regions);
    }

    void refreshSession(Session session, LocalPlayer player, Location location, ApplicableRegionSet regions) {
        VanishHandler handler = session.getHandler(handlerType);
        if (handler != null) handler.initialize(player, location, regions);
    }

    private boolean active(Binding expected) {
        return binding == expected && expected.plugin.isEnabled();
    }

    private void failure(Binding expected, LocalPlayer player, Throwable error) {
        if (expected.failedPlayers.add(player.getUniqueId())) {
            logger.log(Level.WARNING, "@wglog:vanishIntegrationPlayerFailed@" + type.pluginName + ": " + player.getName(), error);
        }
    }

    @Override
    public void close() {
        if (dependencyListener != null) {
            dependencyListener.close();
            dependencyListener = null;
        }
        binding = null;
        sessionManager = null;
        PlayerMoveListener.setMovementFlags(this, Set.of());
    }

    public enum Type {
        PREMIUM("PremiumVanish", "premium-vanish"),
        SUPER("SuperVanish", "super-vanish"),
        CMI("CMI", "cmi-vanish");

        public final String pluginName;
        public final String flagName;

        Type(String pluginName, String flagName) {
            this.pluginName = pluginName;
            this.flagName = flagName;
        }
    }

    interface Provider {
        boolean current(Player player);
        void set(Player player, boolean value);
    }

    private static final class Binding {
        public final Plugin plugin;
        public final Provider provider;
        public final Set<UUID> failedPlayers = ConcurrentHashMap.newKeySet();

        private Binding(Plugin plugin, Provider provider) {
            this.plugin = plugin;
            this.provider = provider;
        }
    }

    private abstract static class VanishHandler extends FlagValueChangeHandler<State> {
        private final VanishIntegration integration;
        private Binding binding;
        private Boolean original;

        private VanishHandler(Session session, VanishIntegration integration) {
            super(session, integration.flag);
            this.integration = integration;
        }

        private void update(LocalPlayer player, @Nullable State state, World world) {
            Binding current = integration.binding;
            if (binding != current) {
                binding = current;
                original = null;
            }
            if (current == null || !integration.active(current)) return;
            Player bukkitPlayer = player instanceof BukkitPlayer local ? local.getPlayer()
                    : integration.owner.getServer().getPlayer(player.getUniqueId());
            if (bukkitPlayer == null) return;
            try {
                if (state == null || getSession().getManager().hasBypass(player, world)) {
                    restore(bukkitPlayer, current);
                } else {
                    boolean value = current.provider.current(bukkitPlayer);
                    if (original == null) original = value;
                    set(bukkitPlayer, current, state == State.ALLOW, value);
                }
            } catch (RuntimeException | LinkageError error) {
                integration.failure(current, player, error);
            }
        }

        private void restore(Player player, Binding current) {
            if (original == null) return;
            set(player, current, original, current.provider.current(player));
            original = null;
        }

        private void set(Player player, Binding current, boolean value, boolean previous) {
            if (value != previous && integration.active(current)) current.provider.set(player, value);
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

    private static final class PremiumVanishHandler extends VanishHandler {
        private PremiumVanishHandler(Session session, VanishIntegration integration) {
            super(session, integration);
        }
    }

    private static final class SuperVanishHandler extends VanishHandler {
        private SuperVanishHandler(Session session, VanishIntegration integration) {
            super(session, integration);
        }
    }

    private static final class CmiVanishHandler extends VanishHandler {
        private CmiVanishHandler(Session session, VanishIntegration integration) {
            super(session, integration);
        }
    }
}
