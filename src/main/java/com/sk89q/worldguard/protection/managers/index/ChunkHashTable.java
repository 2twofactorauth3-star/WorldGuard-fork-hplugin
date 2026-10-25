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

package com.sk89q.worldguard.protection.managers.index;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionDifference;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.QueryBounds;
import com.sk89q.worldguard.protection.util.RegionCollectionConsumer;
import com.sk89q.worldguard.util.collect.LongHashTable;
import com.sk89q.worldguard.util.concurrent.EvenMoreExecutors;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

/**
 * Maintains a hash table for each chunk containing a list of regions that
 * are contained within that chunk, allowing for fast spatial lookup.
 */
public class ChunkHashTable implements ConcurrentRegionIndex {

    private static final ClassValue<Boolean> CUSTOM_CONTAINMENT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("applyContaining", BlockVector3.class, Predicate.class)
                        .getDeclaringClass() != ChunkHashTable.class;
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException(exception);
            }
        }
    };

    private static final int RECENT_CHUNK_CAPACITY = 4096;
    private final String name;
    private ListeningExecutorService executor = createExecutor();
    private LongHashTable<ChunkState> states = new LongHashTable<>();
    private final LinkedHashMap<Long, ChunkState> recentStates =
            new LinkedHashMap<>(256, 0.75f, true);
    private final RegionIndex index;
    private final Object lock = new Object();
    private final ArrayDeque<BlockVector2> pendingChunks = new ArrayDeque<>();
    private long enumerationEpoch;
    private boolean enumerationScheduled;
    private final ThreadLocal<CachedChunkState> lastState = ThreadLocal.withInitial(CachedChunkState::new);
    private volatile long generation;

    /**
     * Create a new instance.
     *
     * @param index the index
     * @param name
     */
    public ChunkHashTable(RegionIndex index, String name) {
        checkNotNull(index);
        this.index = index;
        this.name = name;
    }

    /**
     * Create an executor.
     *
     * @return an executor service
     */
    private ListeningExecutorService createExecutor() {
        return MoreExecutors.listeningDecorator(EvenMoreExecutors.newBoundedCachedThreadPool(0, 4, Integer.MAX_VALUE,
                "WorldGuard Region Chunk Table - " + name));
    }

    /**
     * Get a state object at the given position.
     *
     * @param position the position
     * @param create true to create an entry if one does not exist
     * @return a chunk state object, or {@code null} (only if {@code create} is false)
     */
    @Nullable
    private ChunkState get(BlockVector2 position, boolean create) {
        ChunkState state;
        synchronized (lock) {
            state = states.get(position.x(), position.z());
            if (state == null) {
                long key = chunkKey(position.x(), position.z());
                state = recentStates.get(key);
                if (state != null && create) {
                    recentStates.remove(key);
                    states.put(position.x(), position.z(), state);
                }
            }
            if (state == null && create) {
                state = new ChunkState(position);
                states.put(position.x(), position.z(), state);
                pendingChunks.addLast(position);
                scheduleEnumeration();
            }
        }
        return state;
    }

    @Nullable
    private ChunkState get(int chunkX, int chunkZ) {
        synchronized (lock) {
            ChunkState state = states.get(chunkX, chunkZ);
            return state != null ? state : recentStates.get(chunkKey(chunkX, chunkZ));
        }
    }

    /**
     * Get a state at the given position or create a new entry if one does
     * not exist.
     *
     * @param position the position
     * @return a state
     */
    private void getOrCreate(BlockVector2 position) {
        if (get(position, true) == null) {
            throw new IllegalStateException("Failed to create chunk state");
        }
    }

    private void scheduleEnumeration() {
        if (enumerationScheduled || pendingChunks.isEmpty()) return;
        enumerationScheduled = true;
        long epoch = enumerationEpoch;
        executor.execute(() -> enumeratePending(epoch));
    }

    private void enumeratePending(long epoch) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                List<BlockVector2> batch;
                synchronized (lock) {
                    if (epoch != enumerationEpoch || pendingChunks.isEmpty()) return;
                    batch = new ArrayList<>(Math.min(64, pendingChunks.size()));
                    while (batch.size() < 64 && !pendingChunks.isEmpty()) batch.add(pendingChunks.removeFirst());
                }
                new EnumerateRegions(batch, epoch).run();
            }
        } finally {
            synchronized (lock) {
                if (epoch == enumerationEpoch) {
                    enumerationScheduled = false;
                    if (!executor.isShutdown()) scheduleEnumeration();
                }
            }
        }
    }

    /**
     * Clear the current hash table and rebuild it in the background.
     */
    private void rebuild() {
        synchronized (lock) {
            ListeningExecutorService previousExecutor = executor;
            LongHashTable<ChunkState> previousStates = states;

            previousExecutor.shutdownNow();
            enumerationEpoch++;
            enumerationScheduled = false;
            pendingChunks.clear();
            states = new LongHashTable<>();
            recentStates.clear();
            executor = createExecutor();

            List<BlockVector2> positions = new ArrayList<>();
            for (ChunkState state : previousStates.values()) {
                BlockVector2 position = state.getPosition();
                positions.add(position);
                states.put(position.x(), position.z(), new ChunkState(position));
            }

            if (!positions.isEmpty()) {
                pendingChunks.addAll(positions);
                scheduleEnumeration();
            }

            generation++;
        }
    }

    /**
     * Waits until all currently executing background tasks complete.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if this executor terminated and
     *         {@code false} if the timeout elapsed before termination
     * @throws InterruptedException on interruption
     */
    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        ListeningExecutorService previousExecutor;
        synchronized (lock) {
            previousExecutor = executor;
            executor = createExecutor();
        }
        previousExecutor.shutdown();
        return previousExecutor.awaitTermination(timeout, unit);
    }

    @Override
    public void bias(BlockVector2 chunkPosition) {
        checkNotNull(chunkPosition);
        getOrCreate(chunkPosition);
    }

    @Override
    public void biasAll(Collection<BlockVector2> chunkPositions) {
        synchronized (lock) {
            for (BlockVector2 position : chunkPositions) {
                bias(position);
            }
        }
    }

    @Override
    public void forget(BlockVector2 chunkPosition) {
        checkNotNull(chunkPosition);
        synchronized (lock) {
            ChunkState state = states.get(chunkPosition.x(), chunkPosition.z());
            states.remove(chunkPosition.x(), chunkPosition.z());
            if (state != null && state.isLoaded()) {
                recentStates.put(chunkKey(chunkPosition.x(), chunkPosition.z()), state);
                if (recentStates.size() > RECENT_CHUNK_CAPACITY) {
                    recentStates.remove(recentStates.entrySet().iterator().next().getKey());
                }
            }
        }
    }

    @Override
    public void forgetAll() {
        synchronized (lock) {
            executor.shutdownNow();
            enumerationEpoch++;
            enumerationScheduled = false;
            pendingChunks.clear();
            states = new LongHashTable<>();
            recentStates.clear();
            executor = createExecutor();
            generation++;
        }
    }

    @Override
    public void add(ProtectedRegion region) {
        List<ProtectedRegion> affected = affectedRegions(region);
        index.add(region);
        if (affected == null) rebuild();
        else rebuildAffected(affected);
    }

    @Override
    public void addAll(Collection<ProtectedRegion> regions) {
        index.addAll(regions);
        rebuild();
    }

    @Override
    public Set<ProtectedRegion> remove(String id, RemovalStrategy strategy) {
        Set<ProtectedRegion> removed = index.remove(id, strategy);
        if (!removed.isEmpty()) rebuildAffected(removed);
        return removed;
    }

    private List<ProtectedRegion> affectedRegions(ProtectedRegion region) {
        List<ProtectedRegion> affected = new ArrayList<>();
        for (ProtectedRegion current = region; current != null; current = current.getParent()) {
            ProtectedRegion previous = index.get(current.getId());
            if (previous == current) return null;
            affected.add(current);
            if (previous != null) affected.add(previous);
        }
        return affected;
    }

    private void rebuildAffected(Collection<ProtectedRegion> affected) {
        synchronized (lock) {
            enumerationEpoch++;
            enumerationScheduled = false;
            pendingChunks.clear();
            for (ChunkState state : states.values()) {
                if (affected(state, affected)) {
                    state.loaded = false;
                }
                if (!state.isLoaded()) pendingChunks.addLast(state.getPosition());
            }
            recentStates.values().removeIf(state -> affected(state, affected));
            generation++;
            scheduleEnumeration();
        }
    }

    private static boolean affected(ChunkState state, Collection<ProtectedRegion> regions) {
        int x = state.position.x();
        int z = state.position.z();
        for (ProtectedRegion region : regions) {
            if (!region.isPhysicalArea()) continue;
            BlockVector3 minimum = region.getMinimumPoint();
            BlockVector3 maximum = region.getMaximumPoint();
            if (x >= minimum.x() >> 4 && x <= maximum.x() >> 4
                    && z >= minimum.z() >> 4 && z <= maximum.z() >> 4) return true;
        }
        return false;
    }

    @Override
    public boolean contains(String id) {
        return index.contains(id);
    }

    @Nullable
    @Override
    public ProtectedRegion get(String id) {
        return index.get(id);
    }

    @Override
    public void apply(Predicate<ProtectedRegion> consumer) {
        index.apply(consumer);
    }

    @Override
    public void applyContaining(BlockVector3 position, Predicate<ProtectedRegion> consumer) {
        applyContaining(position, consumer, null);
    }

    @Override
    public void applyContaining(BlockVector3 position, Predicate<ProtectedRegion> consumer, QueryBounds bounds) {
        checkNotNull(position);
        checkNotNull(consumer);
        if (bounds != null && CUSTOM_CONTAINMENT.get(getClass())) {
            bounds.point(position);
            applyContaining(position, consumer);
            return;
        }

        int chunkX = position.x() >> 4;
        int chunkZ = position.z() >> 4;
        CachedChunkState cached = lastState.get();
        ChunkState state = cached.state;
        long currentGeneration = generation;

        if (cached.generation != currentGeneration || cached.chunkX != chunkX || cached.chunkZ != chunkZ
                || state == null) {
            state = cacheChunk(cached, chunkX, chunkZ, currentGeneration);
        }

        if (state != null && state.isLoaded()) {
            if (bounds != null) bounds.chunk(position);
            List<ProtectedRegion> regions = state.getRegions();
            for (ProtectedRegion region : regions) {
                boolean contains = region.contains(position);
                if (bounds != null) bounds.observe(region, contains);
                if (contains && !consumer.test(region)) {
                    if (bounds != null) bounds.restrictToPoint();
                    return;
                }
            }
        } else {
            if (bounds != null) bounds.point(position);
            index.applyContaining(position, consumer);
        }
    }

    @Nullable
    private ChunkState cacheChunk(CachedChunkState cached, int chunkX, int chunkZ, long currentGeneration) {
        ChunkState state;
        if (cached.generation == currentGeneration && cached.previousState != null
                && cached.previousX == chunkX && cached.previousZ == chunkZ) {
            state = cached.previousState;
        } else {
            state = get(chunkX, chunkZ);
        }
        cached.previousState = cached.generation == currentGeneration ? cached.state : null;
        cached.previousX = cached.chunkX;
        cached.previousZ = cached.chunkZ;
        cached.generation = currentGeneration;
        cached.chunkX = chunkX;
        cached.chunkZ = chunkZ;
        cached.state = state;
        return state;
    }

    @Override
    public void applyIntersecting(ProtectedRegion region, Predicate<ProtectedRegion> consumer) {
        index.applyIntersecting(region, consumer);
    }

    @Override
    public int size() {
        return index.size();
    }

    @Override
    public RegionDifference getAndClearDifference() {
        return index.getAndClearDifference();
    }

    @Override
    public void setDirty(RegionDifference difference) {
        index.setDirty(difference);
    }

    @Override
    public Collection<ProtectedRegion> values() {
        return index.values();
    }

    @Override
    public boolean isDirty() {
        return index.isDirty();
    }

    @Override
    public void setDirty(boolean dirty) {
        index.setDirty(dirty);
    }

    /**
     * A task to enumerate the regions for a list of provided chunks.
     */
    private class EnumerateRegions implements Runnable {
        private final List<BlockVector2> positions;
        private final long epoch;

        private EnumerateRegions(List<BlockVector2> positions, long epoch) {
            checkNotNull(positions);
            checkArgument(!positions.isEmpty(), "List of positions can't be empty");
            this.positions = positions;
            this.epoch = epoch;
        }

        @Override
        public void run() {
            for (BlockVector2 position : positions) {
                if (Thread.currentThread().isInterrupted()) return;
                ChunkState state = get(position, false);

                if (state != null) {
                    List<ProtectedRegion> regions = new ArrayList<>();
                    ProtectedRegion chunkRegion = new ProtectedCuboidRegion(
                            "_",
                            position.multiply(16).toBlockVector3(Integer.MIN_VALUE),
                            position.add(1, 1).multiply(16).toBlockVector3(Integer.MAX_VALUE));
                    index.applyIntersecting(chunkRegion, new RegionCollectionConsumer(regions, false));
                    regions.sort(null);

                    synchronized (lock) {
                        if (epoch != enumerationEpoch || Thread.currentThread().isInterrupted()) return;
                        state.setRegions(Collections.unmodifiableList(regions));
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Stores a cache of region data for a chunk.
     */
    private static class ChunkState {
        private final BlockVector2 position;
        private volatile boolean loaded = false;
        private volatile List<ProtectedRegion> regions = Collections.emptyList();

        private ChunkState(BlockVector2 position) {
            this.position = position;
        }

        public BlockVector2 getPosition() {
            return position;
        }

        public List<ProtectedRegion> getRegions() {
            return regions;
        }

        public void setRegions(List<ProtectedRegion> regions) {
            this.regions = regions;
            this.loaded = true;
        }

        public boolean isLoaded() {
            return loaded;
        }
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static class CachedChunkState {
        private long generation = -1;
        private int chunkX;
        private int chunkZ;
        private int previousX;
        private int previousZ;
        private ChunkState previousState;
        @Nullable
        private ChunkState state;
    }

    /**
     * A factory for instances of {@code ChunkHashCache}.
     */
    public static class Factory implements Function<String, ChunkHashTable> {
        private final Function<String, ? extends ConcurrentRegionIndex> supplier;

        public Factory(Function<String, ? extends ConcurrentRegionIndex> supplier) {
            checkNotNull(supplier);
            this.supplier = supplier;
        }

        @Override
        public ChunkHashTable apply(String name) {
            return new ChunkHashTable(supplier.apply(name), name);
        }
    }

}
