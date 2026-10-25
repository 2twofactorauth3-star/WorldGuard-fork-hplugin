package com.sk89q.worldguard.protection.regions;

import com.sk89q.worldedit.math.BlockVector3;

public final class QueryBounds {
    private int x;
    private int y;
    private int z;
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    public void point(BlockVector3 point) {
        x = minX = maxX = point.x();
        y = minY = maxY = point.y();
        z = minZ = maxZ = point.z();
    }

    public void chunk(BlockVector3 point) {
        x = point.x();
        y = point.y();
        z = point.z();
        minX = x & ~15;
        minZ = z & ~15;
        maxX = minX + 15;
        maxZ = minZ + 15;
        minY = Integer.MIN_VALUE;
        maxY = Integer.MAX_VALUE;
    }

    public void observe(ProtectedRegion region, boolean contains) {
        if (region.getClass() != ProtectedCuboidRegion.class
                && region.getClass() != ProtectedPolygonalRegion.class) {
            restrictToPoint();
            return;
        }
        BlockVector3 minimum = region.getMinimumPoint();
        BlockVector3 maximum = region.getMaximumPoint();
        if (x < minimum.x()) maxX = Math.min(maxX, minimum.x() - 1);
        else if (x > maximum.x()) minX = Math.max(minX, maximum.x() + 1);
        else if (y < minimum.y()) maxY = Math.min(maxY, minimum.y() - 1);
        else if (y > maximum.y()) minY = Math.max(minY, maximum.y() + 1);
        else if (z < minimum.z()) maxZ = Math.min(maxZ, minimum.z() - 1);
        else if (z > maximum.z()) minZ = Math.max(minZ, maximum.z() + 1);
        else if (contains && region.getClass() == ProtectedCuboidRegion.class) {
            minX = Math.max(minX, minimum.x());
            minY = Math.max(minY, minimum.y());
            minZ = Math.max(minZ, minimum.z());
            maxX = Math.min(maxX, maximum.x());
            maxY = Math.min(maxY, maximum.y());
            maxZ = Math.min(maxZ, maximum.z());
        } else {
            restrictToPoint();
        }
    }

    public void restrictToPoint() {
        minX = maxX = x;
        minY = maxY = y;
        minZ = maxZ = z;
    }

    public Cell snapshot() {
        return minX == maxX && minY == maxY && minZ == maxZ ? null
                : new Cell(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static final class Cell {
        public final int minX;
        public final int minY;
        public final int minZ;
        public final int maxX;
        public final int maxY;
        public final int maxZ;

        private Cell(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }
}
