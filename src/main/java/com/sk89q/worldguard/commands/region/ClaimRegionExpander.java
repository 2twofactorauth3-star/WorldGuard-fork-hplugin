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

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.config.ClaimExpansion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;

import java.util.function.Predicate;

public final class ClaimRegionExpander {

    private ClaimRegionExpander() {
    }

    public static ProtectedCuboidRegion expand(ProtectedCuboidRegion original,
                                               BlockVector3 worldMinimum,
                                               BlockVector3 worldMaximum,
                                               ClaimExpansion settings,
                                               int volumeLimit,
                                               Predicate<ProtectedCuboidRegion> permitted) {
        if (volumeLimit <= 0 || volume(original) >= volumeLimit) {
            return original;
        }

        ProtectedCuboidRegion expanded = expandAxis(original, Axis.Y,
                settings.negativeY, settings.negativeYMaxDistance,
                settings.positiveY, settings.positiveYMaxDistance,
                worldMinimum.y(), worldMaximum.y(), volumeLimit, permitted);
        expanded = expandAxis(expanded, Axis.X,
                settings.negativeX, settings.negativeXMaxDistance,
                settings.positiveX, settings.positiveXMaxDistance,
                worldMinimum.x(), worldMaximum.x(), volumeLimit, permitted);
        expanded = expandAxis(expanded, Axis.Z,
                settings.negativeZ, settings.negativeZMaxDistance,
                settings.positiveZ, settings.positiveZMaxDistance,
                worldMinimum.z(), worldMaximum.z(), volumeLimit, permitted);

        if (expanded == original) {
            return original;
        }
        expanded.copyFrom(original);
        return expanded;
    }

    public static ProtectedPolygonalRegion expand(ProtectedPolygonalRegion original,
                                                  BlockVector3 worldMinimum,
                                                  BlockVector3 worldMaximum,
                                                  ClaimExpansion settings,
                                                  int volumeLimit,
                                                  Predicate<ProtectedPolygonalRegion> permitted) {
        int originalVolume = original.volume();
        if (originalVolume <= 0 || originalVolume >= volumeLimit
                || (!settings.negativeY && !settings.positiveY)) {
            return original;
        }

        int currentHeight = original.getMaximumPoint().y() - original.getMinimumPoint().y() + 1;
        long footprint = originalVolume / currentHeight;
        long allowedHeight = volumeLimit / footprint;
        long availableByVolume = Math.max(0, allowedHeight - currentHeight);
        int negativeCapacity = capacity(settings.negativeY, settings.negativeYMaxDistance,
                boundedDistance(original.getMinimumPoint().y(), worldMinimum.y()));
        int positiveCapacity = capacity(settings.positiveY, settings.positiveYMaxDistance,
                boundedDistance(worldMaximum.y(), original.getMaximumPoint().y()));
        int total = (int) Math.min(availableByVolume,
                Math.min(Integer.MAX_VALUE, (long) negativeCapacity + positiveCapacity));
        if (total == 0) {
            return original;
        }

        ProtectedPolygonalRegion expanded;
        if (!settings.negativeY) {
            expanded = expandPolygonDirection(
                    original, true, Math.min(total, positiveCapacity), permitted);
        } else if (!settings.positiveY) {
            expanded = expandPolygonDirection(
                    original, false, Math.min(total, negativeCapacity), permitted);
        } else {
            int plannedNegative = Math.min(negativeCapacity, (total + 1) / 2);
            int plannedPositive = Math.min(positiveCapacity, total - plannedNegative);
            int unassigned = total - plannedNegative - plannedPositive;
            if (unassigned > 0) {
                int addNegative = Math.min(unassigned, negativeCapacity - plannedNegative);
                plannedNegative += addNegative;
                unassigned -= addNegative;
                plannedPositive += Math.min(unassigned, positiveCapacity - plannedPositive);
            }

            expanded = expandPolygonDirection(original, false, plannedNegative, permitted);
            int usedNegative = original.getMinimumPoint().y() - expanded.getMinimumPoint().y();
            int positiveTarget = Math.min(
                    positiveCapacity, plannedPositive + plannedNegative - usedNegative);
            ProtectedPolygonalRegion withPositive =
                    expandPolygonDirection(expanded, true, positiveTarget, permitted);
            int usedPositive =
                    withPositive.getMaximumPoint().y() - expanded.getMaximumPoint().y();
            int remaining = total - usedNegative - usedPositive;
            if (remaining > 0) {
                withPositive = expandPolygonDirection(
                        withPositive,
                        false,
                        Math.min(remaining, negativeCapacity - usedNegative),
                        permitted);
            }
            expanded = withPositive;
        }

        if (expanded == original) {
            return original;
        }
        expanded.copyFrom(original);
        return expanded;
    }

    private static ProtectedPolygonalRegion expandPolygonDirection(
            ProtectedPolygonalRegion region,
            boolean positive,
            int maximumDistance,
            Predicate<ProtectedPolygonalRegion> permitted) {
        if (maximumDistance <= 0) {
            return region;
        }

        int low = 0;
        int high = maximumDistance;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            ProtectedPolygonalRegion candidate =
                    withVerticalExpansion(region, positive, middle);
            if (permitted.test(candidate)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low == 0 ? region : withVerticalExpansion(region, positive, low);
    }

    private static ProtectedPolygonalRegion withVerticalExpansion(
            ProtectedPolygonalRegion region, boolean positive, int distance) {
        int minimumY = region.getMinimumPoint().y();
        int maximumY = region.getMaximumPoint().y();
        if (positive) {
            maximumY += distance;
        } else {
            minimumY -= distance;
        }
        return new ProtectedPolygonalRegion(
                region.getId(), region.getPoints(), minimumY, maximumY);
    }

    private static ProtectedCuboidRegion expandAxis(ProtectedCuboidRegion region,
                                                     Axis axis,
                                                     boolean negative,
                                                     int negativeMaxDistance,
                                                     boolean positive,
                                                     int positiveMaxDistance,
                                                     int worldMinimum,
                                                     int worldMaximum,
                                                     int volumeLimit,
                                                     Predicate<ProtectedCuboidRegion> permitted) {
        if (!negative && !positive) {
            return region;
        }

        int axisLength = axis.coordinate(region.getMaximumPoint())
                - axis.coordinate(region.getMinimumPoint()) + 1;
        long crossSection = volume(region) / axisLength;
        long allowedAxisLength = volumeLimit / crossSection;
        long availableByVolume = Math.max(0, allowedAxisLength - axisLength);
        int negativeCapacity = capacity(negative, negativeMaxDistance,
                boundedDistance(axis.coordinate(region.getMinimumPoint()), worldMinimum));
        int positiveCapacity = capacity(positive, positiveMaxDistance,
                boundedDistance(worldMaximum, axis.coordinate(region.getMaximumPoint())));
        int total = (int) Math.min(availableByVolume,
                Math.min(Integer.MAX_VALUE, (long) negativeCapacity + positiveCapacity));
        if (total == 0) {
            return region;
        }

        if (!negative) {
            return expandDirection(region, axis, true, Math.min(total, positiveCapacity), permitted);
        }
        if (!positive) {
            return expandDirection(region, axis, false, Math.min(total, negativeCapacity), permitted);
        }

        int plannedNegative = Math.min(negativeCapacity, (total + 1) / 2);
        int plannedPositive = Math.min(positiveCapacity, total - plannedNegative);
        int unassigned = total - plannedNegative - plannedPositive;
        if (unassigned > 0) {
            int addNegative = Math.min(unassigned, negativeCapacity - plannedNegative);
            plannedNegative += addNegative;
            unassigned -= addNegative;
            plannedPositive += Math.min(unassigned, positiveCapacity - plannedPositive);
        }

        ProtectedCuboidRegion expanded = expandDirection(region, axis, false, plannedNegative, permitted);
        int usedNegative = axis.coordinate(region.getMinimumPoint())
                - axis.coordinate(expanded.getMinimumPoint());
        int positiveTarget = Math.min(positiveCapacity, plannedPositive + plannedNegative - usedNegative);
        ProtectedCuboidRegion withPositive = expandDirection(expanded, axis, true, positiveTarget, permitted);
        int usedPositive = axis.coordinate(withPositive.getMaximumPoint())
                - axis.coordinate(expanded.getMaximumPoint());

        int remaining = total - usedNegative - usedPositive;
        if (remaining > 0) {
            int remainingNegativeCapacity = negativeCapacity - usedNegative;
            withPositive = expandDirection(withPositive, axis, false,
                    Math.min(remaining, remainingNegativeCapacity), permitted);
        }
        return withPositive;
    }

    private static ProtectedCuboidRegion expandDirection(ProtectedCuboidRegion region,
                                                          Axis axis,
                                                          boolean positive,
                                                          int maximumDistance,
                                                          Predicate<ProtectedCuboidRegion> permitted) {
        if (maximumDistance <= 0) {
            return region;
        }

        int low = 0;
        int high = maximumDistance;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            ProtectedCuboidRegion candidate = withExpansion(region, axis, positive, middle);
            if (permitted.test(candidate)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low == 0 ? region : withExpansion(region, axis, positive, low);
    }

    private static ProtectedCuboidRegion withExpansion(ProtectedCuboidRegion region,
                                                        Axis axis,
                                                        boolean positive,
                                                        int distance) {
        BlockVector3 minimum = region.getMinimumPoint();
        BlockVector3 maximum = region.getMaximumPoint();
        if (positive) {
            maximum = axis.withCoordinate(maximum, axis.coordinate(maximum) + distance);
        } else {
            minimum = axis.withCoordinate(minimum, axis.coordinate(minimum) - distance);
        }
        return new ProtectedCuboidRegion(region.getId(), minimum, maximum);
    }

    private static int boundedDistance(int greater, int lesser) {
        return Math.clamp((long) greater - lesser, 0, Integer.MAX_VALUE);
    }

    private static int capacity(boolean enabled, int maximumDistance, int worldCapacity) {
        if (!enabled) {
            return 0;
        }
        return maximumDistance < 0
                ? worldCapacity : Math.min(worldCapacity, maximumDistance);
    }

    private static long volume(ProtectedCuboidRegion region) {
        BlockVector3 minimum = region.getMinimumPoint();
        BlockVector3 maximum = region.getMaximumPoint();
        long x = (long) maximum.x() - minimum.x() + 1;
        long y = (long) maximum.y() - minimum.y() + 1;
        long z = (long) maximum.z() - minimum.z() + 1;
        if (x > Long.MAX_VALUE / y) {
            return Long.MAX_VALUE;
        }
        long xy = x * y;
        return xy > Long.MAX_VALUE / z ? Long.MAX_VALUE : xy * z;
    }

    private enum Axis {
        X {
            @Override
            int coordinate(BlockVector3 vector) {
                return vector.x();
            }

            @Override
            BlockVector3 withCoordinate(BlockVector3 vector, int value) {
                return vector.withX(value);
            }
        },
        Y {
            @Override
            int coordinate(BlockVector3 vector) {
                return vector.y();
            }

            @Override
            BlockVector3 withCoordinate(BlockVector3 vector, int value) {
                return vector.withY(value);
            }
        },
        Z {
            @Override
            int coordinate(BlockVector3 vector) {
                return vector.z();
            }

            @Override
            BlockVector3 withCoordinate(BlockVector3 vector, int value) {
                return vector.withZ(value);
            }
        };

        abstract int coordinate(BlockVector3 vector);

        abstract BlockVector3 withCoordinate(BlockVector3 vector, int value);
    }
}
