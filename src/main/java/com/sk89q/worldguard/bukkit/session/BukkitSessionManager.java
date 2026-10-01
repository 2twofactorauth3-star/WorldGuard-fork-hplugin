/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.bukkit.session;

import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitPlayer;
import com.sk89q.worldguard.bukkit.BukkitRegionContainer;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.event.player.ProcessPlayerEvent;
import com.sk89q.worldguard.session.AbstractSessionManager;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.Set;


/**
 * Keeps tracks of sessions and also does session-related handling
 * (flags, etc.).
 */
public class BukkitSessionManager extends AbstractSessionManager implements Runnable, Listener {

    private static final Set<Flag<?>> TICK_FLAGS = Set.of(
            Flags.HEAL_AMOUNT,
            Flags.HEAL_DELAY,
            Flags.MIN_HEAL,
            Flags.MAX_HEAL,
            Flags.FEED_AMOUNT,
            Flags.FEED_DELAY,
            Flags.MIN_FOOD,
            Flags.MAX_FOOD
    );

    /**
     * Re-initialize handlers and clear "last position," "last state," etc.
     * information for all players.
     */
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void resetAllStates() {
        for (Player player : Bukkit.getServer().getOnlinePlayers()) {
            Runnable task = () -> {
                LocalPlayer bukkitPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
                Session session = getIfPresent(bukkitPlayer);
                if (session != null) {
                    session.resetState(bukkitPlayer);
                }
            };
            if (WorldGuardPlugin.inst().isFolia()) {
                player.getScheduler().run(WorldGuardPlugin.inst(), new Consumer() {
                    @Override
                    public void accept(Object ignored) {
                        task.run();
                    }
                }, null);
            } else {
                task.run();
            }
        }
    }

    @EventHandler
    public void onPlayerProcess(ProcessPlayerEvent event) {
        // Pre-load a session
        LocalPlayer player = WorldGuardPlugin.inst().wrapPlayer(event.getPlayer());
        get(player);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void run() {
        for (Player player : Bukkit.getServer().getOnlinePlayers()) {
            if (!customHandlersRegistered()) {
                RegionManager manager = ((BukkitRegionContainer) WorldGuard.getInstance().getPlatform()
                        .getRegionContainer()).get(player.getWorld());
                if (manager == null || !manager.hasAnyFlag(TICK_FLAGS)) {
                    continue;
                }
            }
            Runnable task = () -> {
                LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
                get(localPlayer).tick(localPlayer);
            };
            if (WorldGuardPlugin.inst().isFolia()) {
                player.getScheduler().run(WorldGuardPlugin.inst(), new Consumer() {
                    @Override
                    public void accept(Object ignored) {
                        task.run();
                    }
                }, null);
            } else {
                task.run();
            }
        }
    }

    @Override
    public boolean hasBypass(LocalPlayer player, World world) {
        if (player instanceof BukkitPlayer bukkitPlayer) {
            if (!bukkitPlayer.getPlayer().isOnline()) {
                return false;
            }
        }
        return super.hasBypass(player, world);
    }

    @Override
    public Session get(LocalPlayer player) {
        if (player instanceof BukkitPlayer bukkitPlayer) {
            Session cached = bukkitPlayer.getWorldGuardSession();
            if (cached != null) {
                return cached;
            }
            Session session = super.get(player);
            bukkitPlayer.setWorldGuardSession(session);
            return session;
        }
        return super.get(player);
    }

    @Override
    @Nullable
    public Session getIfPresent(LocalPlayer player) {
        if (player instanceof BukkitPlayer bukkitPlayer) {
            Session cached = bukkitPlayer.getWorldGuardSession();
            if (cached != null) {
                return cached;
            }
            Session session = super.getIfPresent(player);
            if (session != null) {
                bukkitPlayer.setWorldGuardSession(session);
            }
            return session;
        }
        return super.getIfPresent(player);
    }

    @Override
    public void remove(LocalPlayer player) {
        super.remove(player);
        if (player instanceof BukkitPlayer bukkitPlayer) {
            bukkitPlayer.setWorldGuardSession(null);
        }
    }

    public void shutdown() {
        for (Player player : Bukkit.getServer().getOnlinePlayers()) {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            Session session = getIfPresent(localPlayer);
            if (session != null) {
                session.uninitialize(localPlayer);
            }
        }
        clearSessions();
    }
}
