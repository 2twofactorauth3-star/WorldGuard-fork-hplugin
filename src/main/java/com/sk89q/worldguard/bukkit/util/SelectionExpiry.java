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

package com.sk89q.worldguard.bukkit.util;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.regions.selector.Polygonal2DRegionSelector;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import org.bukkit.Bukkit;

import java.util.List;

/** Clears a consumed WorldEdit selection after the configured delay. */
public final class SelectionExpiry {

    private final Actor actor;
    private final World world;
    private final Snapshot snapshot;

    private SelectionExpiry(Actor actor, World world, Snapshot snapshot) {
        this.actor = actor;
        this.world = world;
        this.snapshot = snapshot;
    }

    public static SelectionExpiry capture(Actor actor, World world) {
        LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(actor);
        if (session == null) {
            return new SelectionExpiry(actor, world, null);
        }
        try {
            Region selection = session.getRegionSelector(world).getRegion();
            return new SelectionExpiry(actor, world, Snapshot.capture(selection));
        } catch (IncompleteRegionException ignored) {
            return new SelectionExpiry(actor, world, null);
        }
    }

    public void schedule() {
        WorldGuardPlugin plugin = WorldGuardPlugin.inst();
        int delaySeconds = plugin.getConfigManager().selectionClearAfterSuccessfulActionSeconds;
        schedule(delaySeconds);
    }

    public void schedule(int delaySeconds) {
        if (snapshot == null) {
            return;
        }
        WorldGuardPlugin plugin = WorldGuardPlugin.inst();
        if (delaySeconds < 0) {
            return;
        }

        long delayTicks = Math.max(1L, delaySeconds * 20L);
        if (plugin.isFolia()) {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(
                    plugin, ignored -> clearIfUnchanged(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, this::clearIfUnchanged, delayTicks);
        }
    }

    public boolean isCurrentSelection() {
        LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(actor);
        if (session == null || session.getSelectionWorld() == null
                || !session.getSelectionWorld().getName().equals(world.getName())) {
            return false;
        }
        try {
            return snapshot != null && snapshot.matches(session.getRegionSelector(world).getRegion());
        } catch (IncompleteRegionException ignored) {
            return false;
        }
    }

    public void clear() {
        clearIfUnchanged();
    }

    private void clearIfUnchanged() {
        if (!isCurrentSelection()) {
            return;
        }

        LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(actor);
        if (session == null) return;
        RegionSelector emptySelector = snapshot.polygon
                ? new Polygonal2DRegionSelector(world)
                : new CuboidRegionSelector(world);
        session.setRegionSelector(world, emptySelector);
    }

    private static final class Snapshot {
        public final boolean polygon;
        public final BlockVector3 minimum;
        public final BlockVector3 maximum;
        public final List<BlockVector2> polygonPoints;

        private Snapshot(boolean polygon, BlockVector3 minimum, BlockVector3 maximum,
                         List<BlockVector2> polygonPoints) {
            this.polygon = polygon;
            this.minimum = minimum;
            this.maximum = maximum;
            this.polygonPoints = polygonPoints;
        }

        static Snapshot capture(Region region) {
            if (region instanceof Polygonal2DRegion polygon) {
                return new Snapshot(true, region.getMinimumPoint(), region.getMaximumPoint(),
                        List.copyOf(polygon.getPoints()));
            }
            if (region instanceof CuboidRegion) {
                return new Snapshot(false, region.getMinimumPoint(), region.getMaximumPoint(), List.of());
            }
            return null;
        }

        boolean matches(Region region) {
            if (!minimum.equals(region.getMinimumPoint()) || !maximum.equals(region.getMaximumPoint())) {
                return false;
            }
            if (polygon) {
                return region instanceof Polygonal2DRegion current
                        && polygonPoints.equals(current.getPoints());
            }
            return region instanceof CuboidRegion;
        }
    }
}
