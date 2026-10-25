package com.sk89q.worldguard.bukkit;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

final class ChunkLoadBatch {
    private final ConcurrentMap<World, Pending> worlds = new ConcurrentHashMap<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Function<World, RegionManager> managers;
    private final Consumer<Runnable> scheduler;

    ChunkLoadBatch(Function<World, RegionManager> managers, Consumer<Runnable> scheduler) {
        this.managers = managers;
        this.scheduler = scheduler;
    }

    void submit(World world, int x, int z, boolean loaded) {
        Pending pending = worlds.computeIfAbsent(world, ignored -> new Pending());
        pending.chunks.put((long) x << 32 | z & 0xffffffffL, loaded);
        schedule();
    }

    void discard(World world) {
        Pending pending = worlds.remove(world);
        if (pending != null) pending.valid = false;
    }

    void clear() {
        worlds.values().forEach(pending -> pending.valid = false);
        worlds.clear();
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) scheduler.accept(this::flush);
    }

    private void flush() {
        try {
            worlds.forEach(this::flushWorld);
        } finally {
            scheduled.set(false);
            for (Pending pending : worlds.values()) {
                if (!pending.chunks.isEmpty()) {
                    schedule();
                    break;
                }
            }
        }
    }

    private void flushWorld(World world, Pending pending) {
        if (!pending.valid || pending.chunks.isEmpty()) return;
        var loads = new ArrayList<BlockVector2>();
        var unloads = new ArrayList<BlockVector2>();
        pending.chunks.forEach((position, loaded) -> {
            if (pending.chunks.remove(position, loaded)) {
                BlockVector2 chunk = BlockVector2.at((int) (position >> 32), (int) (long) position);
                (loaded ? loads : unloads).add(chunk);
            }
        });
        RegionManager manager = managers.apply(world);
        if (manager == null || !pending.valid) return;
        for (BlockVector2 chunk : unloads) manager.unloadChunk(chunk);
        if (!loads.isEmpty()) manager.loadChunks(loads);
    }

    private static final class Pending {
        public final ConcurrentMap<Long, Boolean> chunks = new ConcurrentHashMap<>();
        public volatile boolean valid = true;
    }
}
