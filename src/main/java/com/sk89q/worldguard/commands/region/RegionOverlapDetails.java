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

package com.sk89q.worldguard.commands.region;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.util.ArrayList;
import java.util.List;

final class RegionOverlapDetails {

    private static final long MAX_EXACT_SEARCH_BLOCKS = 65_536;

    private RegionOverlapDetails() {
    }

    static BlockVector3 findIntersectionPoint(ProtectedRegion first, ProtectedRegion second) {
        int minimumX = Math.max(first.getMinimumPoint().x(), second.getMinimumPoint().x());
        int minimumY = Math.max(first.getMinimumPoint().y(), second.getMinimumPoint().y());
        int minimumZ = Math.max(first.getMinimumPoint().z(), second.getMinimumPoint().z());
        int maximumX = Math.min(first.getMaximumPoint().x(), second.getMaximumPoint().x());
        int maximumZ = Math.min(first.getMaximumPoint().z(), second.getMaximumPoint().z());

        List<BlockVector3> candidates = new ArrayList<>();
        candidates.add(BlockVector3.at(minimumX, minimumY, minimumZ));
        candidates.add(BlockVector3.at(maximumX, minimumY, minimumZ));
        candidates.add(BlockVector3.at(minimumX, minimumY, maximumZ));
        candidates.add(BlockVector3.at(maximumX, minimumY, maximumZ));
        candidates.add(BlockVector3.at(
                minimumX + (maximumX - minimumX) / 2,
                minimumY,
                minimumZ + (maximumZ - minimumZ) / 2));
        addRegionPoints(candidates, first, minimumY, minimumX, maximumX, minimumZ, maximumZ);
        addRegionPoints(candidates, second, minimumY, minimumX, maximumX, minimumZ, maximumZ);

        for (BlockVector3 candidate : candidates) {
            if (first.contains(candidate) && second.contains(candidate)) {
                return candidate;
            }
        }

        long width = (long) maximumX - minimumX + 1;
        long depth = (long) maximumZ - minimumZ + 1;
        if (width > 0 && depth > 0 && width <= MAX_EXACT_SEARCH_BLOCKS / depth) {
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    BlockVector3 candidate = BlockVector3.at(x, minimumY, z);
                    if (first.contains(candidate) && second.contains(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        return BlockVector3.at(minimumX, minimumY, minimumZ);
    }

    private static void addRegionPoints(List<BlockVector3> candidates,
                                        ProtectedRegion region,
                                        int y,
                                        int minimumX,
                                        int maximumX,
                                        int minimumZ,
                                        int maximumZ) {
        for (BlockVector2 point : region.getPoints()) {
            int x = Math.clamp(point.x(), minimumX, maximumX);
            int z = Math.clamp(point.z(), minimumZ, maximumZ);
            candidates.add(BlockVector3.at(x, y, z));
        }
    }
}
