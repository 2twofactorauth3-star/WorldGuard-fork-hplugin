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

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.config.SelectionLimit;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SelectionLimitTracker {

    private final WorldGuardPlugin plugin;
    private final ConcurrentMap<UUID, SelectionKey> confirmed = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, SelectionKey> prompted = new ConcurrentHashMap<>();

    public SelectionLimitTracker(WorldGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public Decision evaluate(LocalPlayer player, World world, ProtectedRegion original, int expandedVolume) {
        SelectionLimit limit = plugin.getConfigManager().selectionLimit;
        if (limit == null || !limit.enabled) {
            return Decision.ALLOW;
        }

        int originalVolume = original.volume();
        if (limit.includeClaimExpansion
                && expandedVolume > limit.maximumVolume
                && originalVolume <= limit.maximumVolume) {
            return Decision.WITHOUT_EXPANSION;
        }
        if (originalVolume <= limit.maximumVolume) {
            return Decision.ALLOW;
        }
        if (!limit.canBypass(player)) {
            return Decision.DENY;
        }
        if (!limit.requireBypassConfirmation) {
            return Decision.ALLOW;
        }

        SelectionKey key = SelectionKey.capture(world, original);
        return key.equals(confirmed.get(player.getUniqueId()))
                ? Decision.ALLOW : Decision.CONFIRM;
    }

    public ConfirmationResult confirm(LocalPlayer player, World world, ProtectedRegion selection) {
        SelectionLimit limit = plugin.getConfigManager().selectionLimit;
        if (limit == null || !limit.enabled || selection.volume() <= limit.maximumVolume
                || !limit.requireBypassConfirmation) {
            return ConfirmationResult.NOT_REQUIRED;
        }
        if (!limit.canBypass(player)) {
            return ConfirmationResult.NOT_ALLOWED;
        }
        SelectionKey key = SelectionKey.capture(world, selection);
        confirmed.put(player.getUniqueId(), key);
        prompted.remove(player.getUniqueId());
        return ConfirmationResult.CONFIRMED;
    }

    public boolean markPrompted(LocalPlayer player, World world, ProtectedRegion selection) {
        SelectionKey key = SelectionKey.capture(world, selection);
        SelectionKey previous = prompted.put(player.getUniqueId(), key);
        return !key.equals(previous);
    }

    public void forget(UUID playerId) {
        confirmed.remove(playerId);
        prompted.remove(playerId);
    }

    public void clear() {
        confirmed.clear();
        prompted.clear();
    }

    public enum Decision {
        ALLOW,
        WITHOUT_EXPANSION,
        DENY,
        CONFIRM
    }

    public enum ConfirmationResult {
        CONFIRMED,
        NOT_ALLOWED,
        NOT_REQUIRED
    }

    private record SelectionKey(
            String worldName,
            BlockVector3 minimum,
            BlockVector3 maximum,
            List<BlockVector2> polygonPoints
    ) {
        private static SelectionKey capture(World world, ProtectedRegion region) {
            List<BlockVector2> points = region instanceof ProtectedPolygonalRegion polygon
                    ? List.copyOf(polygon.getPoints()) : List.of();
            return new SelectionKey(
                    world.getName(), region.getMinimumPoint(), region.getMaximumPoint(), points);
        }
    }
}
