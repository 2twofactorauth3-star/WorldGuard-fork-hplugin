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

package com.sk89q.worldguard.config;

/**
 * Selects the directions in which a newly claimed cuboid may use its remaining volume allowance.
 */
public final class ClaimExpansion {

    public final boolean negativeY;
    public final boolean positiveY;
    public final boolean negativeX;
    public final boolean positiveX;
    public final boolean negativeZ;
    public final boolean positiveZ;
    public final int negativeYMaxDistance;
    public final int positiveYMaxDistance;
    public final int negativeXMaxDistance;
    public final int positiveXMaxDistance;
    public final int negativeZMaxDistance;
    public final int positiveZMaxDistance;

    public ClaimExpansion(boolean negativeY, boolean positiveY,
                          boolean negativeX, boolean positiveX,
                          boolean negativeZ, boolean positiveZ) {
        this(negativeY, -1, positiveY, -1,
                negativeX, -1, positiveX, -1,
                negativeZ, -1, positiveZ, -1);
    }

    public ClaimExpansion(boolean negativeY, int negativeYMaxDistance,
                          boolean positiveY, int positiveYMaxDistance,
                          boolean negativeX, int negativeXMaxDistance,
                          boolean positiveX, int positiveXMaxDistance,
                          boolean negativeZ, int negativeZMaxDistance,
                          boolean positiveZ, int positiveZMaxDistance) {
        this.negativeY = negativeY;
        this.positiveY = positiveY;
        this.negativeX = negativeX;
        this.positiveX = positiveX;
        this.negativeZ = negativeZ;
        this.positiveZ = positiveZ;
        this.negativeYMaxDistance = normalizeDistance(negativeYMaxDistance);
        this.positiveYMaxDistance = normalizeDistance(positiveYMaxDistance);
        this.negativeXMaxDistance = normalizeDistance(negativeXMaxDistance);
        this.positiveXMaxDistance = normalizeDistance(positiveXMaxDistance);
        this.negativeZMaxDistance = normalizeDistance(negativeZMaxDistance);
        this.positiveZMaxDistance = normalizeDistance(positiveZMaxDistance);
    }

    private static int normalizeDistance(int distance) {
        return Math.max(-1, distance);
    }
}
