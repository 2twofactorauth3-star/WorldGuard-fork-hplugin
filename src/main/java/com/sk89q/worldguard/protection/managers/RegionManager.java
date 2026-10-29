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

package com.sk89q.worldguard.protection.managers;

import static com.google.common.base.Preconditions.checkNotNull;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.RegionResultSet;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.managers.index.ConcurrentRegionIndex;
import com.sk89q.worldguard.protection.managers.index.RegionIndex;
import com.sk89q.worldguard.protection.managers.storage.DifferenceSaveException;
import com.sk89q.worldguard.protection.managers.storage.RegionDatabase;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.QueryBounds;
import com.sk89q.worldguard.protection.regions.RegionQuery.QueryOption;
import com.sk89q.worldguard.util.Normal;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A region manager holds the regions for a world.
 */
public final class RegionManager {

    private final RegionDatabase store;
    private final Function<String, ? extends ConcurrentRegionIndex> indexFactory;
    private final FlagRegistry flagRegistry;
    private ConcurrentRegionIndex index;
    private final AtomicLong queryRevision = new AtomicLong();
    private final ThreadLocal<HighestPriorityRegion> highestPriorityRegion =
            ThreadLocal.withInitial(HighestPriorityRegion::new);
    private volatile long observedFlagRevision = -1;
    private volatile long observedFlagUsageQueryRevision = -1;
    private volatile Map<Flag<?>, Integer> flagUsage = Collections.emptyMap();
    private final Object resultCacheLock = new Object();
    private volatile ResultCache resultCache;
    private final ThreadLocal<RegionCollector> regionCollectors = ThreadLocal.withInitial(RegionCollector::new);

    /**
     * Create a new index.
     *
     * @param store the region store
     * @param indexFactory the factory for creating new instances of the index
     * @param flagRegistry the flag registry
     */
    public RegionManager(RegionDatabase store, Function<String, ? extends ConcurrentRegionIndex> indexFactory, FlagRegistry flagRegistry) {
        checkNotNull(store);
        checkNotNull(indexFactory);
        checkNotNull(flagRegistry, "flagRegistry");

        this.store = store;
        this.indexFactory = indexFactory;
        this.index = indexFactory.apply(store.getName());
        this.flagRegistry = flagRegistry;
    }

    /**
     * Get a displayable name for this store.
     */
    public String getName() {
        return store.getName();
    }

    /**
     * Load regions from storage and replace the index on this manager with
     * the regions loaded from the store.
     *
     * <p>This method will block until the save completes, but it will
     * not block access to the region data from other threads, nor will it
     * prevent the creation or modification of regions in the index while
     * a new collection of regions is loaded from storage.</p>
     *
     * @throws StorageException thrown when loading fails
     */
    public void load() throws StorageException {
        Set<ProtectedRegion> regions = store.loadAll(flagRegistry);
        for (ProtectedRegion region : regions) {
            region.setDirty(false);
        }
        setRegions(regions);
    }

    /**
     * Save a snapshot of all the regions as it is right now to storage.
     *
     * @throws StorageException thrown on save error
     */
    public void save() throws StorageException {
        index.setDirty(false);
        store.saveAll(getFilteredValuesCopy());
    }

    /**
     * Save changes to the region index to disk, preferring to only save
     * the changes (rather than the whole index), but choosing to save the
     * whole index if the underlying store does not support partial saves.
     *
     * <p>This method does nothing if there are no changes.</p>
     *
     * @return true if there were changes to be saved
     * @throws StorageException thrown on save error
     */
    @SuppressWarnings("UnusedReturnValue") // Kept for API compatibility with external integrations.
    public boolean saveChanges() throws StorageException {
        RegionDifference diff = index.getAndClearDifference();
        boolean successful = false;

        try {
            if (diff.containsChanges()) {
                try {
                    store.saveChanges(diff);
                } catch (DifferenceSaveException e) {
                    save(); // Partial save is not supported
                }
                successful = true;
                return true;
            } else {
                successful = true;
                return false;
            }
        } finally {
            if (!successful) {
                index.setDirty(diff);
            }
        }
    }

    /**
     * Load the regions for a chunk.
     *
     * @param position the position
     */
    public void loadChunk(BlockVector2 position) {
        index.bias(position);
    }

    /**
     * Load the regions for a chunk.
     *
     * @param positions a collection of positions
     */
    public void loadChunks(Collection<BlockVector2> positions) {
        index.biasAll(positions);
    }

    /**
     * Unload the regions for a chunk.
     *
     * @param position the position
     */
    public void unloadChunk(BlockVector2 position) {
        index.forget(position);
    }

    /**
     * Get an unmodifiable map of regions containing the state of the
     * index at the time of call.
     *
     * <p>This call is relatively heavy (and may block other threads),
     * so refrain from calling it frequently.</p>
     *
     * @return a map of regions
     */
    public Map<String, ProtectedRegion> getRegions() {
        Map<String, ProtectedRegion> map = new HashMap<>();
        for (ProtectedRegion region : index.values()) {
            map.put(Normal.normalize(region.getId()), region);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Replace the index with the regions in the given map.
     *
     * <p>The parents of the regions will also be added to the index, even
     * if they are not in the provided map.</p>
     *
     * @param regions a map of regions
     */
    public void setRegions(Map<String, ProtectedRegion> regions) {
        checkNotNull(regions);

        setRegions(regions.values());
    }

    /**
     * Replace the index with the regions in the given collection.
     *
     * <p>The parents of the regions will also be added to the index, even
     * if they are not in the provided map.</p>
     *
     * @param regions a collection of regions
     */
    public void setRegions(Collection<ProtectedRegion> regions) {
        checkNotNull(regions);

        ConcurrentRegionIndex newIndex = indexFactory.apply(getName());
        newIndex.addAll(regions);
        newIndex.getAndClearDifference(); // Clear changes
        this.index = newIndex;
        queryRevision.incrementAndGet();
    }

    /**
     * Add a region to the manager.
     *
     * <p>The parents of the region will also be added to the index.</p>
     *
     * @param region the region
     */
    public void addRegion(ProtectedRegion region) {
        checkNotNull(region);
        index.add(region);
        queryRevision.incrementAndGet();
    }

    /**
     * Return whether the index contains a region by the given name,
     * with equality determined by {@link Normal}.
     *
     * @param id the name of the region
     * @return true if this index contains the region
     */
    public boolean hasRegion(String id) {
        return index.contains(id);
    }

    /**
     * Get the region named by the given name (equality determined using
     * {@link Normal}).
     *
     * @param id the name of the region
     * @return a region or {@code null}
     */
    @Nullable
    public ProtectedRegion matchRegion(String pattern) {
        return getRegion(pattern);
    }

    @Nullable
    public ProtectedRegion getRegion(String id) {
        checkNotNull(id);
        return index.get(id);
    }

    /**
     * Remove a region from the index with the given name, opting to remove
     * the children of the removed region.
     *
     * @param id the name of the region
     */
    public Set<ProtectedRegion> removeRegion(String id) {
        return removeRegion(id, RemovalStrategy.REMOVE_CHILDREN);
    }

    /**
     * Remove a region from the index with the given name.
     *
     * @param id the name of the region
     * @param strategy what to do with children
     * @return a list of removed regions where the first entry is the region specified by {@code id}
     */
    public Set<ProtectedRegion> removeRegion(String id, RemovalStrategy strategy) {
        Set<ProtectedRegion> removed = index.remove(id, strategy);
        if (!removed.isEmpty()) {
            queryRevision.incrementAndGet();
        }
        return removed;
    }

    public long getQueryRevision() {
        return queryRevision.get();
    }

    public boolean hasAnyFlag(Set<? extends Flag<?>> flags) {
        Map<Flag<?>, Integer> usage = currentFlagUsage();
        for (Flag<?> flag : flags) {
            if (usage.containsKey(flag)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasFlag(Flag<?> flag) {
        return currentFlagUsage().containsKey(flag);
    }

    public boolean hasState(StateFlag flag, State state) {
        int mask = currentFlagUsage().getOrDefault(flag, 0);
        return state == State.ALLOW ? (mask & 1) != 0 : (mask & 2) != 0;
    }

    private Map<Flag<?>, Integer> currentFlagUsage() {
        long revision = ProtectedRegion.flagRevision();
        long currentQueryRevision = queryRevision.get();
        Map<Flag<?>, Integer> current = flagUsage;
        if (observedFlagRevision == revision && observedFlagUsageQueryRevision == currentQueryRevision) {
            return current;
        }
        synchronized (this) {
            if (observedFlagRevision == revision && observedFlagUsageQueryRevision == currentQueryRevision) {
                return flagUsage;
            }
            Map<Flag<?>, Integer> rebuilt = new HashMap<>();
            for (ProtectedRegion region : index.values()) {
                for (Map.Entry<Flag<?>, Object> entry : region.getFlags().entrySet()) {
                    int bit = 4;
                    if (entry.getValue() == State.ALLOW) {
                        bit = 1;
                    } else if (entry.getValue() == State.DENY) {
                        bit = 2;
                    }
                    rebuilt.merge(entry.getKey(), bit, (left, right) -> left | right);
                }
            }
            flagUsage = Collections.unmodifiableMap(rebuilt);
            observedFlagRevision = revision;
            observedFlagUsageQueryRevision = currentQueryRevision;
            return flagUsage;
        }
    }

    /**
     * Query for effective flags and members for the given position.
     *
     * <p>{@link QueryOption#COMPUTE_PARENTS} is used.</p>
     *
     * @param position the position
     * @return the query object
     */
    public ApplicableRegionSet getApplicableRegions(BlockVector3 position) {
        return getApplicableRegions(position, QueryOption.COMPUTE_PARENTS);
    }

    @Nullable
    public ProtectedRegion getHighestPriorityRegion(BlockVector3 position) {
        HighestPriorityRegion selection = highestPriorityRegion.get();
        selection.region = null;
        try {
            index.applyContaining(position, selection);
            return selection.region == null ? index.get(ProtectedRegion.GLOBAL_REGION) : selection.region;
        } finally {
            selection.region = null;
        }
    }

    private static final class HighestPriorityRegion implements Predicate<ProtectedRegion> {
        private ProtectedRegion region;

        @Override
        public boolean test(ProtectedRegion candidate) {
            if (candidate.isPhysicalArea() && (region == null || candidate.getPriority() > region.getPriority()
                    || candidate.getPriority() == region.getPriority()
                    && candidate.getId().compareTo(region.getId()) < 0)) {
                region = candidate;
            }
            return true;
        }
    }

    /**
     * Return a region set for the given position.
     *
     * @param position the position
     * @param option the option
     * @return a region set
     */
    public ApplicableRegionSet getApplicableRegions(BlockVector3 position, QueryOption option) {
        return getApplicableRegions(position, option, null);
    }

    public ApplicableRegionSet getApplicableRegions(BlockVector3 position, QueryOption option, QueryBounds bounds) {
        checkNotNull(position);
        checkNotNull(option);

        RegionCollector collector = borrowCollector(option);
        try {
            if (bounds == null) index.applyContaining(position, collector);
            else index.applyContaining(position, collector, bounds);
            return createResult(collector, option);
        } finally {
            collector.release();
        }
    }

    /**
     * Query for effective flags and members for the area represented
     * by the given region.
     *
     * <p>{@link QueryOption#COMPUTE_PARENTS} is used.</p>
     *
     * @param region the region
     * @return the query object
     */
    public ApplicableRegionSet getApplicableRegions(ProtectedRegion region) {
        return getApplicableRegions(region, QueryOption.COMPUTE_PARENTS);
    }

    /**
     * Return a region set for the area represented by the given region.
     *
     * @param region the region
     * @param option the option
     * @return a region set
     */
    public ApplicableRegionSet getApplicableRegions(ProtectedRegion region, QueryOption option) {
        checkNotNull(region);
        checkNotNull(option);

        RegionCollector collector = borrowCollector(option);
        try {
            index.applyIntersecting(region, collector);
            return createResult(collector, option);
        } finally {
            collector.release();
        }
    }

    private RegionCollector borrowCollector(QueryOption option) {
        RegionCollector collector = regionCollectors.get();
        if (collector.inUse) collector = new RegionCollector();
        collector.inUse = true;
        collector.parents = option == QueryOption.COMPUTE_PARENTS;
        return collector;
    }

    private ApplicableRegionSet createResult(RegionCollector collector, QueryOption option) {
        if (collector.size <= 1) {
            ResultCache cache = currentResultCache();
            ProtectedRegion only = collector.first;
            if (only == null) return cache.empty;
            ApplicableRegionSet result = cache.singletons.get(only);
            if (result != null) return result;
            return cache.singletons.computeIfAbsent(only, ignored -> {
                Set<ProtectedRegion> singleton = Collections.singleton(only);
                return RegionResultSet.fromSnapshot(Collections.singletonList(only), singleton, cache.global);
            });
        }
        ResultCache cache = collector.size <= 16 ? currentResultCache() : null;
        if (cache != null) {
            ApplicableRegionSet result = cache.combinations.get(collector);
            if (result != null) return result;
        }
        Set<ProtectedRegion> regions = collector.snapshot();
        Set<ProtectedRegion> retained = Collections.unmodifiableSet(regions);
        ApplicableRegionSet result = RegionResultSet.fromSnapshot(
                (cache != null ? QueryOption.SORT : option).constructResult(regions), retained,
                cache != null ? cache.global : index.get(ProtectedRegion.GLOBAL_REGION));
        return cache == null ? result : cache.remember(retained, collector.hashCode(), result);
    }

    private ResultCache currentResultCache() {
        long query = queryRevision.get();
        long structure = ProtectedRegion.structureRevision();
        ResultCache cached = resultCache;
        if (cached != null && cached.queryRevision == query && cached.structureRevision == structure) {
            return cached;
        }
        synchronized (resultCacheLock) {
            cached = resultCache;
            if (cached == null || cached.queryRevision != query || cached.structureRevision != structure) {
                cached = new ResultCache(query, structure, index.get(ProtectedRegion.GLOBAL_REGION));
                resultCache = cached;
            }
            return cached;
        }
    }

    private static final class ResultCache {
        public final long queryRevision;
        public final long structureRevision;
        public final ProtectedRegion global;
        public final ApplicableRegionSet empty;
        public final ConcurrentMap<ProtectedRegion, ApplicableRegionSet> singletons = new ConcurrentHashMap<>();
        public final ConcurrentMap<RegionKey, ApplicableRegionSet> combinations = new ConcurrentHashMap<>();
        private final AtomicInteger combinationCount = new AtomicInteger();

        private ResultCache(long queryRevision, long structureRevision, @Nullable ProtectedRegion global) {
            this.queryRevision = queryRevision;
            this.structureRevision = structureRevision;
            this.global = global;
            this.empty = RegionResultSet.fromSnapshot(Collections.emptyList(), Collections.emptySet(), global);
        }

        private ApplicableRegionSet remember(Set<ProtectedRegion> regions, int hash, ApplicableRegionSet result) {
            if (combinationCount.incrementAndGet() > 256) {
                combinationCount.decrementAndGet();
                return result;
            }
            ApplicableRegionSet existing = combinations.putIfAbsent(new RegionCombination(regions, hash), result);
            if (existing == null) return result;
            combinationCount.decrementAndGet();
            return existing;
        }
    }

    private interface RegionKey {
        boolean matches(RegionCollector collector);
    }

    private static final class RegionCollector implements Predicate<ProtectedRegion>, RegionKey {
        private ProtectedRegion first;
        private ProtectedRegion second;
        private ProtectedRegion third;
        private ProtectedRegion fourth;
        private Set<ProtectedRegion> multiple;
        private int size;
        private int hash;
        private boolean parents;
        private boolean inUse;

        @Override
        public boolean test(ProtectedRegion region) {
            add(region);
            if (parents) {
                ProtectedRegion parent = region.getParent();
                while (parent != null) {
                    add(parent);
                    parent = parent.getParent();
                }
            }
            return true;
        }

        private void add(ProtectedRegion region) {
            if (first == null) {
                first = region;
                size = 1;
            } else if (multiple != null) {
                if (multiple.add(region)) {
                    size++;
                    hash += region.hashCode();
                }
            } else if (!region.equals(first) && !region.equals(second)
                    && !region.equals(third) && !region.equals(fourth)) {
                addDistinct(region);
            }
        }

        private void addDistinct(ProtectedRegion region) {
            switch (size) {
                case 1 -> {
                    second = region;
                    hash = first.hashCode();
                }
                case 2 -> third = region;
                case 3 -> fourth = region;
                default -> {
                    multiple = snapshot();
                    multiple.add(region);
                }
            }
            size++;
            hash += region.hashCode();
        }

        private Set<ProtectedRegion> snapshot() {
            if (multiple != null) return multiple;
            Set<ProtectedRegion> result = new HashSet<>();
            result.add(first);
            if (second != null) result.add(second);
            if (third != null) result.add(third);
            if (fourth != null) result.add(fourth);
            return result;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean matches(RegionCollector collector) {
            if (size != collector.size) return false;
            if (multiple != null) return multiple.equals(collector.multiple);
            return containsOptional(collector.first) && containsOptional(collector.second)
                    && containsOptional(collector.third) && containsOptional(collector.fourth);
        }

        private boolean containsOptional(@Nullable ProtectedRegion region) {
            return region == null || region.equals(first) || region.equals(second)
                    || region.equals(third) || region.equals(fourth);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof RegionKey key && key.matches(this);
        }

        private void release() {
            first = null;
            second = null;
            third = null;
            fourth = null;
            multiple = null;
            size = 0;
            hash = 0;
            inUse = false;
        }
    }

    private static final class RegionCombination implements RegionKey {
        public final Set<ProtectedRegion> regions;
        public final int hash;

        private RegionCombination(Set<ProtectedRegion> regions, int hash) {
            this.regions = regions;
            this.hash = hash;
        }

        @Override
        public boolean matches(RegionCollector collector) {
            if (regions.size() != collector.size) return false;
            if (collector.multiple != null) return regions.equals(collector.multiple);
            return regions.contains(collector.first) && regions.contains(collector.second)
                    && (collector.third == null || regions.contains(collector.third))
                    && (collector.fourth == null || regions.contains(collector.fourth));
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other instanceof RegionCollector collector) return matches(collector);
            return other instanceof RegionCombination combination && regions.equals(combination.regions);
        }
    }

    /**
     * Get a list of region names for regions that contain the given position.
     *
     * @param position the position
     * @return a list of names
     */
    public List<String> getApplicableRegionsIDs(BlockVector3 position) {
        checkNotNull(position);

        final List<String> names = new ArrayList<>();

        index.applyContaining(position, region -> names.add(region.getId()));

        return names;
    }

    /**
     * Return whether there are any regions intersecting the given region that
     * are not owned by the given player.
     *
     * @param region the region
     * @param player the player
     * @return true if there are such intersecting regions
     */
    public boolean overlapsUnownedRegion(ProtectedRegion region, final LocalPlayer player) {
        checkNotNull(region);
        checkNotNull(player);

        RegionIndex index = this.index;

        final AtomicBoolean overlapsUnowned = new AtomicBoolean();

        index.applyIntersecting(region, test -> {
            if (!test.getOwners().contains(player)) {
                overlapsUnowned.set(true);
                return false;
            } else {
                return true;
            }
        });

        return overlapsUnowned.get();
    }

    /**
     * Get the number of regions.
     *
     * @return the number of regions
     */
    public int size() {
        return index.size();
    }

    /**
     * Get the number of regions that are owned by the given player.
     *
     * @param player the player
     * @return name number of regions that a player owns
     */
    public int getRegionCountOfPlayer(final LocalPlayer player) {
        checkNotNull(player);

        final AtomicInteger count = new AtomicInteger();

        index.apply(test -> {
            if (test.getOwners().contains(player)) {
                count.incrementAndGet();
            }
            return true;
        });

        return count.get();
    }

    /**
     * Get a {@link HashSet} copy of regions in the index with transient regions filtered.
     *
     * @return a set
     */
    private Set<ProtectedRegion> getFilteredValuesCopy() {
        Set<ProtectedRegion> filteredValues = new HashSet<>();
        for (ProtectedRegion region : index.values()) {
            if (!region.isTransient()) {
                filteredValues.add(region);
            }
        }
        return filteredValues;
    }

}
