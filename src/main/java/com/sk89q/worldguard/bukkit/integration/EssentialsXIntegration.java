/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.sk89q.worldguard.bukkit.integration;

import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
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

public final class EssentialsXIntegration implements AutoCloseable {

    private final Plugin owner;
    private final Logger logger;
    private final StateFlag vanishFlag;
    private final StateFlag godFlag;
    private final Function<Plugin, Provider> providerFactory;
    private final Function<Player, LocalPlayer> playerWrapper;
    private final Handler.Factory<VanishStateHandler> vanishFactory;
    private final Handler.Factory<GodStateHandler> godFactory;
    private volatile Binding binding;
    private AbstractSessionManager sessionManager;
    private PluginDependencyListener dependencyListener;

    private EssentialsXIntegration(Plugin owner, StateFlag vanishFlag, StateFlag godFlag, Logger logger,
                                   Function<Plugin, Provider> providerFactory,
                                   Function<Player, LocalPlayer> playerWrapper) {
        this.owner = owner;
        this.logger = logger;
        this.vanishFlag = vanishFlag;
        this.godFlag = godFlag;
        this.providerFactory = providerFactory;
        this.playerWrapper = playerWrapper;
        vanishFactory = new Handler.Factory<>() {
            @Override
            public VanishStateHandler create(Session session) {
                return new VanishStateHandler(session, EssentialsXIntegration.this, vanishFlag);
            }
        };
        godFactory = new Handler.Factory<>() {
            @Override
            public GodStateHandler create(Session session) {
                return new GodStateHandler(session, EssentialsXIntegration.this, godFlag);
            }
        };
    }

    @Nullable
    public static EssentialsXIntegration prepare(Plugin owner, FlagRegistry registry, Logger logger) {
        return prepare(owner, registry, logger, EssentialsXAdapter::create,
                player -> WorldGuardPlugin.inst().wrapPlayer(player));
    }

    @Nullable
    static EssentialsXIntegration prepare(Plugin owner, FlagRegistry registry, Logger logger,
                                         Function<Plugin, Provider> providerFactory,
                                         Function<Player, LocalPlayer> playerWrapper) {
        StateFlag vanish = stateFlag(registry, "vanish", logger);
        StateFlag god = stateFlag(registry, "god", logger);
        return vanish == null || god == null ? null
                : new EssentialsXIntegration(owner, vanish, god, logger, providerFactory, playerWrapper);
    }

    @Nullable
    public static EssentialsXIntegration create(Plugin plugin, FlagRegistry registry, Logger logger) {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (dependency == null || !dependency.isEnabled()) return null;
        EssentialsXIntegration integration = prepare(plugin, registry, logger);
        return integration != null && integration.connect(dependency) ? integration : null;
    }

    public void start(AbstractSessionManager manager) {
        registerHandlers(manager);
        if (dependencyListener != null) return;
        dependencyListener = new PluginDependencyListener(owner, "Essentials", this::connect, this::disconnect);
        dependencyListener.start();
        if (binding == null) logger.info("@wglog:essentialsIntegrationWaiting@");
    }

    public void registerHandlers(AbstractSessionManager manager) {
        if (sessionManager == manager) return;
        manager.registerTrackedHandler(vanishFactory, GameModeFlag.FACTORY);
        manager.registerTrackedHandler(godFactory, vanishFactory);
        sessionManager = manager;
        if (binding != null) refreshPlayers(binding);
    }

    boolean connect(Plugin dependency) {
        if (!dependency.isEnabled()) return false;
        Binding current = binding;
        if (current != null && current.plugin == dependency) return true;
        Provider provider;
        try {
            provider = providerFactory.apply(dependency);
        } catch (RuntimeException | LinkageError error) {
            logger.log(Level.WARNING, "@wglog:essentialsIntegrationIncompatible@", error);
            return false;
        }
        if (provider == null) {
            logger.warning("@wglog:essentialsIntegrationIncompatible@");
            return false;
        }
        if (!dependency.isEnabled()) return false;
        Binding connected = new Binding(dependency, provider);
        binding = connected;
        PlayerMoveListener.setMovementFlags(this, Set.of(vanishFlag, godFlag));
        refreshPlayers(connected);
        logger.info("@wglog:essentialsIntegrationEnabled@");
        return true;
    }

    void disconnect(Plugin dependency) {
        Binding current = binding;
        if (current == null || current.plugin != dependency) return;
        binding = null;
        PlayerMoveListener.setMovementFlags(this, Set.of());
        refreshPlayers(null);
        logger.info("@wglog:essentialsIntegrationDisabled@");
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
        Handler vanish = session.getHandler(VanishStateHandler.class);
        Handler god = session.getHandler(GodStateHandler.class);
        if (vanish != null) vanish.initialize(player, location, regions);
        if (god != null) god.initialize(player, location, regions);
    }

    private boolean active(Binding expected) {
        return binding == expected && expected.plugin.isEnabled();
    }

    private void failure(Binding expected, LocalPlayer player, Throwable error) {
        if (expected.failedPlayers.add(player.getUniqueId())) {
            logger.log(Level.WARNING, "@wglog:essentialsIntegrationPlayerFailed@" + player.getName(), error);
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

    @Nullable
    private static StateFlag stateFlag(FlagRegistry registry, String name, Logger logger) {
        Flag<?> existing = registry.get(name);
        if (existing instanceof StateFlag stateFlag) return stateFlag;
        if (existing != null) {
            logger.warning("Cannot register EssentialsX flag '" + name + "': another flag uses this name");
            return null;
        }
        StateFlag flag = new StateFlag(name, false);
        registry.register(flag);
        return flag;
    }

    enum Property {
        VANISH,
        GOD
    }

    interface Provider {
        @Nullable UserState user(LocalPlayer player);
    }

    interface UserState {
        boolean current(Property property);
        void set(Property property, boolean value);
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

    private abstract static class EssentialsStateHandler extends FlagValueChangeHandler<State> {

        private final EssentialsXIntegration integration;
        private final Property property;
        private Binding binding;
        private Boolean original;

        private EssentialsStateHandler(Session session, EssentialsXIntegration integration,
                                       StateFlag flag, Property property) {
            super(session, flag);
            this.integration = integration;
            this.property = property;
        }

        private void update(LocalPlayer player, @Nullable State state, World world) {
            Binding current = integration.binding;
            if (binding != current) {
                binding = current;
                original = null;
            }
            if (current == null || !integration.active(current)) return;
            try {
                UserState user = current.provider.user(player);
                if (user == null || !integration.active(current)) return;
                if (state == null || getSession().getManager().hasBypass(player, world)) {
                    restore(user, current);
                } else {
                    boolean value = user.current(property);
                    if (original == null) original = value;
                    set(user, state == State.ALLOW, value, current);
                }
            } catch (RuntimeException | LinkageError error) {
                integration.failure(current, player, error);
            }
        }

        private void restore(UserState user, Binding current) {
            if (original == null) return;
            set(user, original, user.current(property), current);
            original = null;
        }

        private void set(UserState user, boolean value, boolean previous, Binding current) {
            if (previous != value && integration.active(current)) user.set(property, value);
        }

        @Override
        protected void onInitialValue(LocalPlayer player, ApplicableRegionSet set, State value) {
            update(player, value, player.getWorld());
        }

        @Override
        protected boolean onSetValue(LocalPlayer player, Location from, Location to,
                                     ApplicableRegionSet toSet, State currentValue,
                                     State lastValue, MoveType moveType) {
            update(player, currentValue, (World) to.getExtent());
            return true;
        }

        @Override
        protected boolean onAbsentValue(LocalPlayer player, Location from, Location to,
                                        ApplicableRegionSet toSet, State lastValue, MoveType moveType) {
            update(player, null, (World) to.getExtent());
            return true;
        }
    }

    private static final class VanishStateHandler extends EssentialsStateHandler {
        private VanishStateHandler(Session session, EssentialsXIntegration integration, StateFlag flag) {
            super(session, integration, flag, Property.VANISH);
        }
    }

    private static final class GodStateHandler extends EssentialsStateHandler {
        private GodStateHandler(Session session, EssentialsXIntegration integration, StateFlag flag) {
            super(session, integration, flag, Property.GOD);
        }
    }
}
