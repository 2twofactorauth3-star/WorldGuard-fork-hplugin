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

package com.sk89q.worldguard.bukkit;

import static com.google.common.base.Preconditions.checkNotNull;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

public class BukkitRegionContainer extends RegionContainer {

    private final WorldGuardPlugin plugin;
    private final boolean active;
    private final ConcurrentMap<UUID, RegionManager> managersByWorld = new ConcurrentHashMap<>();
    private final AtomicLong managerRevision = new AtomicLong();
    private final ThreadLocal<ManagerCache> localManager = ThreadLocal.withInitial(ManagerCache::new);
    private final ChunkLoadBatch chunkLoads;

    /**
     * Create a new instance.
     *
     * @param plugin the plugin
     */
    public BukkitRegionContainer(WorldGuardPlugin plugin) {
        this(plugin, true);
    }

    @SuppressWarnings("this-escape")
    public BukkitRegionContainer(WorldGuardPlugin plugin, boolean active) {
        this.plugin = plugin;
        this.active = active;
        chunkLoads = new ChunkLoadBatch(this::get, task -> {
            if (plugin.isFolia()) plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), 1);
            else Bukkit.getScheduler().runTask(plugin, task);
        });
    }

    @Override
    public void initialize() {
        super.initialize();
        if (!active) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onWorldLoad(WorldLoadEvent event) {
                load(BukkitAdapter.adapt(event.getWorld()));
                managerRevision.incrementAndGet();
            }

            @EventHandler
            public void onWorldUnload(WorldUnloadEvent event) {
                chunkLoads.discard(event.getWorld());
                World world = BukkitAdapter.adapt(event.getWorld());
                managersByWorld.remove(event.getWorld().getUID());
                cache.invalidate(world);
                unload(world);
                managerRevision.incrementAndGet();
            }

            @EventHandler
            public void onChunkLoad(ChunkLoadEvent event) {
                Chunk chunk = event.getChunk();
                chunkLoads.submit(event.getWorld(), chunk.getX(), chunk.getZ(), true);
            }

            @EventHandler
            public void onChunkUnload(ChunkUnloadEvent event) {
                Chunk chunk = event.getChunk();
                chunkLoads.submit(event.getWorld(), chunk.getX(), chunk.getZ(), false);
            }
        }, plugin);

    }

    public void shutdown() {
        chunkLoads.clear();
        managersByWorld.clear();
        managerRevision.incrementAndGet();
        container.shutdown();
    }

    @Nullable
    public RegionManager get(org.bukkit.World world) {
        long revision = managerRevision.get();
        ManagerCache local = localManager.get();
        if (local.world == world && local.revision == revision && local.manager != null) {
            return local.manager;
        }
        RegionManager cached = managersByWorld.get(world.getUID());
        if (cached != null) {
            local.set(world, cached, revision);
            return cached;
        }
        RegionManager manager = super.get(BukkitAdapter.adapt(world));
        if (manager != null) {
            managersByWorld.putIfAbsent(world.getUID(), manager);
        }
        local.set(world, manager, revision);
        return manager;
    }

    @Override
    public long getRevision() {
        return managerRevision.get();
    }

    public com.sk89q.worldedit.util.Location adapt(org.bukkit.Location location) {
        org.bukkit.World world = location.getWorld();
        ManagerCache local = localManager.get();
        World adapted = local.world == world ? local.adaptedWorld : null;
        if (adapted == null) {
            adapted = BukkitAdapter.adapt(world);
            if (local.world == world) local.adaptedWorld = adapted;
        }
        return new com.sk89q.worldedit.util.Location(adapted, BukkitAdapter.asVector(location),
                location.getYaw(), location.getPitch());
    }

    @Override
    public void unload(World world) {
        if (world instanceof BukkitWorld bukkitWorld) chunkLoads.discard(bukkitWorld.getWorld());
        managersByWorld.clear();
        managerRevision.incrementAndGet();
        cache.invalidate(world);
        super.unload(world);
    }

    @Override
    public void unload() {
        chunkLoads.clear();
        managersByWorld.clear();
        managerRevision.incrementAndGet();
        super.unload();
    }

    @Override
    public void reload() {
        managersByWorld.clear();
        managerRevision.incrementAndGet();
        super.reload();
    }

    private static final class ManagerCache {

        private org.bukkit.World world;
        private RegionManager manager;
        private World adaptedWorld;
        private long revision = -1;

        private void set(org.bukkit.World world, @Nullable RegionManager manager, long revision) {
            if (this.world != world) adaptedWorld = null;
            this.world = world;
            this.manager = manager;
            this.revision = revision;
        }
    }

    @Override
    @Nullable
    protected RegionManager load(World world) {
        checkNotNull(world);
        cache.invalidate(world);
        if (!active) {
            return null;
        }

        WorldConfiguration config = WorldGuard.getInstance().getPlatform().getGlobalStateManager().get(world);
        if (!config.useRegions) {
            return null;
        }

        RegionManager manager;

        synchronized (lock) {
            manager = container.load(world.getName());

            if (manager != null) {
                ProtectedRegion globalRegion = manager.getRegion(ProtectedRegion.GLOBAL_REGION);
                boolean newGlobalRegion = globalRegion == null;
                if (globalRegion == null) {
                    globalRegion = new GlobalProtectedRegion(ProtectedRegion.GLOBAL_REGION);
                    manager.addRegion(globalRegion);
                }
                if (newGlobalRegion) {
                    config.globalRegionDefaults.applyToGlobalRegion(globalRegion);
                } else if (config.applyRegionDefaultsToExistingRegions) {
                    config.globalRegionDefaults.applyToExistingGlobalRegion(globalRegion);
                }
                if (config.applyRegionDefaultsToExistingRegions) {
                    for (ProtectedRegion region : manager.getRegions().values()) {
                        if (!region.getId().equals(ProtectedRegion.GLOBAL_REGION)) {
                            config.newRegionDefaults.applyToExistingRegion(region, manager);
                        }
                    }
                }

                // Bias the region data for loaded chunks
                List<BlockVector2> positions = new ArrayList<>();
                for (Chunk chunk : ((BukkitWorld) world).getWorld().getLoadedChunks()) {
                    positions.add(BlockVector2.at(chunk.getX(), chunk.getZ()));
                }
                manager.loadChunks(positions);
            }
        }

        managersByWorld.clear();
        if (manager != null && world instanceof BukkitWorld bukkitWorld) {
            managersByWorld.put(bukkitWorld.getWorld().getUID(), manager);
        }
        managerRevision.incrementAndGet();
        return manager;
    }

}
