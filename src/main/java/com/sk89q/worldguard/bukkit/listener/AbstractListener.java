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

package com.sk89q.worldguard.bukkit.listener;

import static com.google.common.base.Preconditions.checkNotNull;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitConfigurationManager;
import com.sk89q.worldguard.bukkit.BukkitPlayer;
import com.sk89q.worldguard.bukkit.BukkitRegionContainer;
import com.sk89q.worldguard.bukkit.BukkitWorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.cause.Cause;
import com.sk89q.worldguard.bukkit.util.Entities;
import com.sk89q.worldguard.config.WorldMechanicSetting;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.association.DelayedRegionOverlapAssociation;
import com.sk89q.worldguard.protection.association.Associables;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

/**
 * Abstract listener to ease creation of listeners.
 */
class AbstractListener implements Listener {

    private final WorldGuardPlugin plugin;
    private static final ThreadLocal<WorldConfigCache> WORLD_CONFIG_CACHE =
            ThreadLocal.withInitial(WorldConfigCache::new);

    /**
     * Construct the listener.
     *
     * @param plugin an instance of WorldGuardPlugin
     */
    public AbstractListener(WorldGuardPlugin plugin) {
        checkNotNull(plugin);
        this.plugin = plugin;
    }

    /**
     * Register events.
     */
    public void registerEvents() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Get the plugin.
     *
     * @return the plugin
     */
    protected static WorldGuardPlugin getPlugin() {
        return WorldGuardPlugin.inst();
    }

    /**
     * Get the global configuration.
     *
     * @return the configuration
     */
    protected static BukkitConfigurationManager getConfig() {
        return getPlugin().getConfigManager();
    }

    /**
     * Get the world configuration given a world.
     *
     * @param world The world to get the configuration for.
     * @return The configuration for {@code world}
     */
    protected static BukkitWorldConfiguration getWorldConfig(String world) {
        return getConfig().get(world);
    }

    protected static BukkitWorldConfiguration getWorldConfig(org.bukkit.World world) {
        BukkitConfigurationManager manager = getConfig();
        WorldConfigCache cache = WORLD_CONFIG_CACHE.get();
        long revision = manager.getRevision();
        if (cache.world == world && cache.revision == revision) {
            return cache.configuration;
        }
        BukkitWorldConfiguration configuration = manager.get(world);
        cache.world = world;
        cache.configuration = configuration;
        cache.revision = revision;
        return configuration;
    }

    /**
     * Get the world configuration given a player.
     *
     * @param player The player to get the wold from
     * @return The {@link WorldConfiguration} for the player's world
     */
    protected static BukkitWorldConfiguration getWorldConfig(LocalPlayer player) {
        return getWorldConfig(((BukkitPlayer) player).getPlayer().getWorld());
    }

    /**
     * Return whether region support is enabled.
     *
     * @param world the world
     * @return true if region support is enabled
     */
    protected static boolean isRegionSupportEnabled(org.bukkit.World world) {
        return getWorldConfig(world).useRegions;
    }

    protected static RegionManager getRegionManager(org.bukkit.World world) {
        return ((BukkitRegionContainer) WorldGuard.getInstance().getPlatform()
                .getRegionContainer()).get(world);
    }

    protected RegionAssociable createRegionAssociable(Cause cause) {
        Object rootCause = cause.getRootCause();

        if (!cause.isKnown()) {
            return Associables.constant(Association.NON_MEMBER);
        } else if (rootCause instanceof Player player && !Entities.isNPC(player)) {
            return getPlugin().wrapPlayer(player);
        } else if (rootCause instanceof OfflinePlayer offlinePlayer) {
            return getPlugin().wrapOfflinePlayer(offlinePlayer);
        } else if (rootCause instanceof Entity entity) {
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            Location loc = entity.getOrigin();
            // Origin world may be null, and thus a Location with a null world created, which cannot be adapted to a WorldEdit location
            if (loc == null || loc.getWorld() == null) {
                loc = entity.getLocation();
            }
            return new DelayedRegionOverlapAssociation(query, BukkitAdapter.adapt(loc));
        } else if (rootCause instanceof Block block) {
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            Location loc = block.getLocation();
            return new DelayedRegionOverlapAssociation(query, BukkitAdapter.adapt(loc));
        } else {
            return Associables.constant(Association.NON_MEMBER);
        }
    }

    protected boolean blocksMechanic(WorldMechanicSetting setting, Location target, Cause cause) {
        return blocksMechanic(setting, target, createRegionAssociable(cause));
    }

    protected boolean blocksMechanic(WorldMechanicSetting setting, Location target) {
        return blocksMechanic(setting, target, Associables.constant(Association.NON_MEMBER));
    }

    protected boolean blocksMechanic(
            WorldMechanicSetting setting,
            Location target,
            RegionAssociable associable
    ) {
        if (!setting.enable) {
            return false;
        }
        if (setting.mode == WorldMechanicSetting.Mode.EVERYWHERE) {
            return true;
        }

        BukkitWorldConfiguration worldConfig = getWorldConfig(target.getWorld());
        if (!worldConfig.useRegions) {
            return setting.mode == WorldMechanicSetting.Mode.OUTSIDE_REGIONS;
        }

        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        com.sk89q.worldedit.util.Location adapted = BukkitAdapter.adapt(target);
        ApplicableRegionSet regions = query.getApplicableRegions(adapted);
        boolean hasLocalRegion = false;
        for (ProtectedRegion region : regions) {
            if (!ProtectedRegion.GLOBAL_REGION.equals(region.getId())) {
                hasLocalRegion = true;
                break;
            }
        }

        if (setting.mode == WorldMechanicSetting.Mode.OUTSIDE_REGIONS) {
            return !hasLocalRegion;
        }
        return hasLocalRegion && !regions.testState(associable, Flags.BUILD);
    }

    private static final class WorldConfigCache {
        private org.bukkit.World world;
        private BukkitWorldConfiguration configuration;
        private long revision = -1;
    }
}
