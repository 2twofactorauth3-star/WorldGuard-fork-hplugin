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
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.RegionResultSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionQuery.QueryOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps short-lived region-query results in independent per-world caches.
 *
 * <p>The outer cache is concurrent for Folia and asynchronous API consumers.
 * Thread-local caches remove map lookups for repeated flag checks and movement
 * within a proven spatial cell. Each entry stores three direct result slots
 * instead of a second map keyed by {@link QueryOption}.</p>
 */
public final class QueryCache {
    private final ConcurrentMap<World, WorldCache> worlds = new ConcurrentHashMap<>();
    private final ThreadLocal<RecentQueries> recent = ThreadLocal.withInitial(RecentQueries::new);
    private volatile long invalidationRevision;

    public ApplicableRegionSet queryContains(RegionManager manager, Location location, QueryOption option) {
        return queryContains(manager, location, option, null);
    }

    public ApplicableRegionSet queryContains(RegionManager manager, Location location, QueryOption option, Cursor cursor) {
        checkNotNull(manager);
        checkNotNull(location);
        checkNotNull(option);
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (!isPackable(x, y, z)) return manager.getApplicableRegions(BlockVector3.at(x, y, z), option);
        long position = pack(x, y, z);
        RecentQueries local = recent.get();
        LocalEntry personal = cursor == null ? null : cursor.recent.get();
        WorldCache world = worldCache(manager, (World) location.getExtent(), local, personal);
        while (true) {
            Generation generation = world.current();
            CacheEntry entry = personal == null ? null : personal.find(world, generation, position, x, y, z);
            if (entry == null) entry = local.find(world, generation, position, x, y, z);
            if (entry == null) entry = generation.getOrCreate(mix(position));
            ApplicableRegionSet result = entry.result(option);
            if (result == null) {
                result = entry.get(option, manager, location);
                if (!usable(world, manager)) {
                    world = worldCache(manager, (World) location.getExtent(), local, null);
                    continue;
                }
                if (generation != world.current()) continue;
            }
            local.remember(world, generation, position, entry);
            if (personal != null) personal.set(world, generation, position, entry);
            return result;
        }
    }

    private WorldCache worldCache(RegionManager manager, World world, RecentQueries local, LocalEntry personal) {
        if (personal != null && usable(personal.world, manager)) return personal.world;
        for (LocalEntry entry : local.entries) if (usable(entry.world, manager)) return entry.world;
        WorldCache existing = worlds.get(world);
        if (usable(existing, manager)) return existing;
        return worlds.compute(world, (ignored, current) -> {
            if (usable(current, manager)) return current;
            if (current != null) current.retire();
            return new WorldCache(manager, invalidationRevision, this);
        });
    }

    private boolean usable(WorldCache cache, RegionManager manager) {
        return cache != null && cache.owner == this && cache.manager == manager && !cache.retired
                && cache.ownerRevision == invalidationRevision;
    }

    public void invalidate(World world) {
        WorldCache removed = worlds.remove(world);
        if (removed != null) removed.retire();
    }

    public synchronized void invalidateAll() {
        invalidationRevision++;
        worlds.forEach((world, cache) -> cache.retire());
        worlds.clear();
        recent.remove();
    }

    private static boolean isPackable(int x, int y, int z) {
        return x >= -33554432 && x <= 33554431 && z >= -33554432 && z <= 33554431
                && y >= -2048 && y <= 2047;
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    public static final class Cursor {
        private final ThreadLocal<LocalEntry> recent = ThreadLocal.withInitial(LocalEntry::new);
    }

    private static final class WorldCache {
        public final RegionManager manager;
        public final long ownerRevision;
        public final QueryCache owner;
        private volatile Generation generation;
        private volatile boolean retired;
        private long sequence;

        private WorldCache(RegionManager manager, long ownerRevision, QueryCache owner) {
            this.manager = manager;
            this.ownerRevision = ownerRevision;
            this.owner = owner;
            generation = fresh();
        }

        private Generation fresh() {
            return new Generation(++sequence, manager.getQueryRevision(), ProtectedRegion.structureRevision());
        }

        private synchronized void retire() {
            retired = true;
            generation = fresh();
        }

        private Generation current() {
            Generation current = generation;
            if (current.managerRevision == manager.getQueryRevision()
                    && current.structureRevision == ProtectedRegion.structureRevision()) return current;
            synchronized (this) {
                current = generation;
                if (current.managerRevision != manager.getQueryRevision()
                        || current.structureRevision != ProtectedRegion.structureRevision()) generation = current = fresh();
                return current;
            }
        }
    }

    private static final class Generation {
        private static final int CAPACITY = 32768;
        public final long id;
        public final long managerRevision;
        public final long structureRevision;
        private volatile Maps maps = new Maps(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());

        private Generation(long id, long managerRevision, long structureRevision) {
            this.id = id;
            this.managerRevision = managerRevision;
            this.structureRevision = structureRevision;
        }

        private CacheEntry getOrCreate(long key) {
            Maps current = maps;
            CacheEntry entry = current.current.get(key);
            if (entry == null) entry = current.previous.get(key);
            if (entry != null) return entry;
            CacheEntry replacement = new CacheEntry();
            CacheEntry raced = current.current.putIfAbsent(key, replacement);
            if (raced != null) return raced;
            if (current.size.incrementAndGet() >= CAPACITY) rotate(current);
            return replacement;
        }

        private synchronized void rotate(Maps current) {
            if (maps == current && current.size.get() >= CAPACITY) {
                maps = new Maps(new ConcurrentHashMap<>(), current.current);
            }
        }
    }

    private static final class Maps {
        public final ConcurrentMap<Long, CacheEntry> current;
        public final ConcurrentMap<Long, CacheEntry> previous;
        public final AtomicInteger size = new AtomicInteger();

        private Maps(ConcurrentMap<Long, CacheEntry> current, ConcurrentMap<Long, CacheEntry> previous) {
            this.current = current;
            this.previous = previous;
        }
    }

    private static final class LocalEntry {
        private WorldCache world;
        private long generation;
        private long position;
        private CacheEntry entry;

        private CacheEntry find(WorldCache world, Generation generation, long position, int x, int y, int z) {
            if (this.world != world || this.generation != generation.id) return null;
            QueryBounds.Cell cell = entry.cell;
            return this.position == position || cell != null && cell.contains(x, y, z) ? entry : null;
        }

        private void set(WorldCache world, Generation generation, long position, CacheEntry entry) {
            this.world = world;
            this.generation = generation.id;
            this.position = position;
            this.entry = entry;
        }
    }

    private static final class RecentQueries {
        public final LocalEntry[] entries = {new LocalEntry(), new LocalEntry(), new LocalEntry(), new LocalEntry()};
        private int next;

        private CacheEntry find(WorldCache world, Generation generation, long position, int x, int y, int z) {
            for (LocalEntry entry : entries) {
                CacheEntry result = entry.find(world, generation, position, x, y, z);
                if (result != null) return result;
            }
            return null;
        }

        private void remember(WorldCache world, Generation generation, long position, CacheEntry entry) {
            entries[next++ & 3].set(world, generation, position, entry);
        }
    }

    private static final class CacheEntry {
        private volatile ApplicableRegionSet none;
        private volatile ApplicableRegionSet sorted;
        private volatile ApplicableRegionSet parents;
        private volatile QueryBounds.Cell cell;

        private ApplicableRegionSet get(QueryOption option, RegionManager manager, Location location) {
            ApplicableRegionSet cached = result(option);
            if (cached != null) return cached;
            synchronized (this) {
                cached = result(option);
                if (cached != null) return cached;
                return switch (option) {
                    case NONE -> none = compute(manager, location, option);
                    case SORT -> sorted = none == null ? none = compute(manager, location, option)
                            : new RegionResultSet(none.getRegions(), manager.getRegion(ProtectedRegion.GLOBAL_REGION));
                    case COMPUTE_PARENTS -> parents = computeParents(manager, location);
                };
            }
        }

        private ApplicableRegionSet result(QueryOption option) {
            return switch (option) {
                case NONE -> none;
                case SORT -> sorted;
                case COMPUTE_PARENTS -> parents;
            };
        }

        private ApplicableRegionSet compute(RegionManager manager, Location location, QueryOption option) {
            BlockVector3 point = location.toVector().toBlockPoint();
            QueryBounds bounds = new QueryBounds();
            ApplicableRegionSet result = manager.getApplicableRegions(point, option, bounds);
            cell = bounds.snapshot();
            return result;
        }

        private ApplicableRegionSet computeParents(RegionManager manager, Location location) {
            if (none == null) {
                return compute(manager, location, QueryOption.COMPUTE_PARENTS);
            }
            Set<ProtectedRegion> regions = new HashSet<>(none.getRegions());
            for (ProtectedRegion region : none) {
                for (ProtectedRegion parent = region.getParent(); parent != null; parent = parent.getParent()) regions.add(parent);
            }
            return new RegionResultSet(regions, manager.getRegion(ProtectedRegion.GLOBAL_REGION));
        }
    }
}
