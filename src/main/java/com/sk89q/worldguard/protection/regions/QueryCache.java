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

package com.sk89q.worldguard.protection.regions;

import static com.google.common.base.Preconditions.checkNotNull;

import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.RegionResultSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionQuery.QueryOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps short-lived region-query results in independent per-world caches.
 *
 * <p>The outer cache is concurrent for Folia and asynchronous API consumers.
 * A thread-local one-entry cache removes map lookups for repeated flag checks
 * at the same block. Each block stores three direct result slots instead of a
 * second map keyed by {@link QueryOption}.</p>
 */
public final class QueryCache {

    private final ConcurrentMap<World, WorldCache> worlds = new ConcurrentHashMap<>();
    private final ThreadLocal<LastQuery> lastQuery = ThreadLocal.withInitial(LastQuery::new);

    public ApplicableRegionSet queryContains(
            RegionManager manager, Location location, QueryOption option) {
        checkNotNull(manager);
        checkNotNull(location);
        checkNotNull(option);

        World world = (World) location.getExtent();
        long position = pack(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        LastQuery local = lastQuery.get();
        WorldCache worldCache = local.worldCache;
        if (worldCache != null && worldCache.manager == manager) {
            worldCache.refreshIfStale();
        }
        if (worldCache != null
                && worldCache.manager == manager
                && local.generation == worldCache.generation
                && local.position == position) {
            return local.entry.get(option, manager, location);
        }

        while (true) {
            worldCache = worlds.get(world);
            if (worldCache == null) {
                WorldCache replacement = new WorldCache(manager);
                WorldCache raced = worlds.putIfAbsent(world, replacement);
                worldCache = raced == null ? replacement : raced;
            }
            if (worldCache.manager == manager) {
                worldCache.refreshIfStale();
                break;
            }
            WorldCache replacement = new WorldCache(manager);
            if (worlds.replace(world, worldCache, replacement)) {
                worldCache.invalidate();
                worldCache = replacement;
                break;
            }
        }

        CacheEntry entry = worldCache.getOrCreate(position);
        local.set(worldCache, position, entry);
        return entry.get(option, manager, location);
    }

    public void invalidate(World world) {
        WorldCache removed = worlds.remove(world);
        if (removed != null) {
            removed.invalidate();
        }
    }

    public void invalidateAll() {
        for (WorldCache cache : worlds.values()) {
            cache.invalidate();
        }
        worlds.clear();
        lastQuery.remove();
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | (y & 0xFFFL);
    }

    private static final class WorldCache {
        private static final int GENERATION_CAPACITY = 32768;

        public final RegionManager manager;
        private volatile ConcurrentMap<Long, CacheEntry> currentEntries = new ConcurrentHashMap<>();
        private volatile ConcurrentMap<Long, CacheEntry> previousEntries = new ConcurrentHashMap<>();
        private final AtomicInteger currentSize = new AtomicInteger();
        public volatile long generation;
        private volatile long managerRevision;
        private volatile long structureRevision;

        private WorldCache(RegionManager manager) {
            this.manager = manager;
            managerRevision = manager.getQueryRevision();
            structureRevision = ProtectedRegion.structureRevision();
        }

        private synchronized void invalidate() {
            generation++;
            currentEntries = new ConcurrentHashMap<>();
            previousEntries = new ConcurrentHashMap<>();
            currentSize.set(0);
        }

        private CacheEntry getOrCreate(long position) {
            CacheEntry entry = currentEntries.get(position);
            if (entry != null) {
                return entry;
            }
            entry = previousEntries.get(position);
            if (entry != null) {
                return entry;
            }

            CacheEntry replacement = new CacheEntry();
            CacheEntry raced = currentEntries.putIfAbsent(position, replacement);
            if (raced != null) {
                return raced;
            }
            if (currentSize.incrementAndGet() >= GENERATION_CAPACITY) {
                rotateEntries();
            }
            return replacement;
        }

        private void rotateEntries() {
            synchronized (this) {
                if (currentSize.get() < GENERATION_CAPACITY) {
                    return;
                }
                previousEntries = currentEntries;
                currentEntries = new ConcurrentHashMap<>();
                currentSize.set(0);
            }
        }

        private void refreshIfStale() {
            long currentManagerRevision = manager.getQueryRevision();
            long currentStructureRevision = ProtectedRegion.structureRevision();
            if (managerRevision == currentManagerRevision
                    && structureRevision == currentStructureRevision) {
                return;
            }
            synchronized (this) {
                currentManagerRevision = manager.getQueryRevision();
                currentStructureRevision = ProtectedRegion.structureRevision();
                if (managerRevision != currentManagerRevision
                        || structureRevision != currentStructureRevision) {
                    managerRevision = currentManagerRevision;
                    structureRevision = currentStructureRevision;
                    invalidate();
                }
            }
        }
    }

    private static final class LastQuery {
        private WorldCache worldCache;
        private long generation;
        private long position;
        private CacheEntry entry;

        private void set(WorldCache worldCache, long position, CacheEntry entry) {
            this.worldCache = worldCache;
            this.generation = worldCache.generation;
            this.position = position;
            this.entry = entry;
        }
    }

    private static final class CacheEntry {
        private volatile ApplicableRegionSet none;
        private volatile ApplicableRegionSet sorted;
        private volatile ApplicableRegionSet parents;

        private ApplicableRegionSet get(
                QueryOption option, RegionManager manager, Location location) {
            ApplicableRegionSet cached = switch (option) {
                case NONE -> none;
                case SORT -> sorted;
                case COMPUTE_PARENTS -> parents;
            };
            if (cached != null) {
                return cached;
            }
            synchronized (this) {
                return switch (option) {
                    case NONE -> none != null ? none : computeNone(manager, location);
                    case SORT -> sorted != null ? sorted : computeSorted(manager, location);
                    case COMPUTE_PARENTS ->
                            parents != null ? parents : computeParents(manager, location);
                };
            }
        }

        private ApplicableRegionSet computeNone(RegionManager manager, Location location) {
            none = manager.getApplicableRegions(
                    location.toVector().toBlockPoint(), QueryOption.NONE);
            return none;
        }

        private ApplicableRegionSet computeSorted(RegionManager manager, Location location) {
            if (none == null) {
                ApplicableRegionSet result = manager.getApplicableRegions(
                        location.toVector().toBlockPoint(), QueryOption.SORT);
                none = result;
                sorted = result;
            } else {
                sorted = new RegionResultSet(
                        none.getRegions(), manager.getRegion(ProtectedRegion.GLOBAL_REGION));
            }
            return sorted;
        }

        private ApplicableRegionSet computeParents(RegionManager manager, Location location) {
            parents = manager.getApplicableRegions(
                    location.toVector().toBlockPoint(), QueryOption.COMPUTE_PARENTS);
            return parents;
        }
    }
}
