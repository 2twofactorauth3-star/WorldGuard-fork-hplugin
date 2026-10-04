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

import com.earth2me.essentials.User;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
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
import net.ess3.api.IEssentials;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.util.logging.Logger;

public final class EssentialsXIntegration {

    private final VanishStateHandler.Factory vanishFactory;
    private final GodStateHandler.Factory godFactory;

    private EssentialsXIntegration(IEssentials essentials, StateFlag vanishFlag, StateFlag godFlag) {
        vanishFactory = new VanishStateHandler.Factory(essentials, vanishFlag);
        godFactory = new GodStateHandler.Factory(essentials, godFlag);
    }

    @Nullable
    public static EssentialsXIntegration create(Plugin plugin, FlagRegistry registry, Logger logger) {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (!(dependency instanceof IEssentials essentials) || !dependency.isEnabled()) {
            return null;
        }
        StateFlag vanish = stateFlag(registry, "vanish", logger);
        StateFlag god = stateFlag(registry, "god", logger);
        if (vanish == null || god == null) {
            return null;
        }
        PlayerMoveListener.registerMovementFlag(vanish);
        PlayerMoveListener.registerMovementFlag(god);
        return new EssentialsXIntegration(essentials, vanish, god);
    }

    public void registerHandlers(AbstractSessionManager sessionManager) {
        sessionManager.registerTrackedHandler(vanishFactory, GameModeFlag.FACTORY);
        sessionManager.registerTrackedHandler(godFactory, vanishFactory);
    }

    @Nullable
    private static StateFlag stateFlag(FlagRegistry registry, String name, Logger logger) {
        Flag<?> existing = registry.get(name);
        if (existing instanceof StateFlag stateFlag) {
            return stateFlag;
        }
        if (existing != null) {
            logger.warning("Cannot register EssentialsX flag '" + name + "': another flag uses this name");
            return null;
        }
        StateFlag flag = new StateFlag(name, false);
        registry.register(flag);
        return flag;
    }

    private enum Property {
        VANISH,
        GOD
    }

    private abstract static class EssentialsStateHandler extends FlagValueChangeHandler<State> {

        private final IEssentials essentials;
        private final Property property;
        private Boolean original;

        private EssentialsStateHandler(Session session, IEssentials essentials,
                                       StateFlag flag, Property property) {
            super(session, flag);
            this.essentials = essentials;
            this.property = property;
        }

        private void update(LocalPlayer player, @Nullable State state, World world) {
            User user = user(player);
            if (user == null) {
                return;
            }
            if (state == null || getSession().getManager().hasBypass(player, world)) {
                restore(user);
                return;
            }
            if (original == null) {
                original = current(user);
            }
            set(user, state == State.ALLOW);
        }

        private void restore(User user) {
            if (original != null) {
                boolean value = original;
                original = null;
                set(user, value);
            }
        }

        @Nullable
        private User user(LocalPlayer player) {
            Player bukkitPlayer = Bukkit.getPlayer(player.getUniqueId());
            return bukkitPlayer == null ? null : essentials.getUser(bukkitPlayer);
        }

        private boolean current(User user) {
            return property == Property.VANISH ? user.isVanished() : user.isGodModeEnabled();
        }

        private void set(User user, boolean value) {
            if (current(user) == value) {
                return;
            }
            if (property == Property.VANISH) {
                user.setVanished(value);
            } else {
                user.setGodModeEnabled(value);
            }
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
                                        ApplicableRegionSet toSet, State lastValue,
                                        MoveType moveType) {
            update(player, null, (World) to.getExtent());
            return true;
        }

    }

    private static final class VanishStateHandler extends EssentialsStateHandler {

        private VanishStateHandler(Session session, IEssentials essentials, StateFlag flag) {
            super(session, essentials, flag, Property.VANISH);
        }

        private static final class Factory extends Handler.Factory<VanishStateHandler> {

            private final IEssentials essentials;
            private final StateFlag flag;

            private Factory(IEssentials essentials, StateFlag flag) {
                this.essentials = essentials;
                this.flag = flag;
            }

            @Override
            public VanishStateHandler create(Session session) {
                return new VanishStateHandler(session, essentials, flag);
            }
        }
    }

    private static final class GodStateHandler extends EssentialsStateHandler {

        private GodStateHandler(Session session, IEssentials essentials, StateFlag flag) {
            super(session, essentials, flag, Property.GOD);
        }

        private static final class Factory extends Handler.Factory<GodStateHandler> {

            private final IEssentials essentials;
            private final StateFlag flag;

            private Factory(IEssentials essentials, StateFlag flag) {
                this.essentials = essentials;
                this.flag = flag;
            }

            @Override
            public GodStateHandler create(Session session) {
                return new GodStateHandler(session, essentials, flag);
            }
        }
    }
}
