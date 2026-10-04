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

package com.sk89q.worldguard.bukkit.util;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.commands.region.ClaimRegionExpander;
import com.sk89q.worldguard.config.ConfigurationManager;
import com.sk89q.worldguard.config.SelectionParticleMode;
import com.sk89q.worldguard.config.SelectionLimit;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Draws completed WorldEdit selections for their owners with client-side particles.
 */
public final class SelectionVisualizer {

    private static final int MAX_POLYGON_VERTICES = 24;
    private static final int MAX_CHUNK_CUBES = 1024;
    private static final long DISCOVERY_PERIOD_TICKS = 20;

    private final WorldGuardPlugin plugin;
    private final ConfigurationManager settings;
    private final ConcurrentMap<UUID, CachedSelection> activeSelections = new ConcurrentHashMap<>();
    private volatile ParticleSettings particleSettings;

    public SelectionVisualizer(WorldGuardPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getConfigManager();
        this.particleSettings = ParticleSettings.from(settings);
    }

    public void start() {
        long updatePeriodTicks = Math.max(1, settings.selectionParticleUpdatePeriodTicks);
        if (plugin.isFolia()) {
            plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> {
                refreshSettings();
                if (!shouldProcessSelections()) {
                    return;
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.getScheduler().run(plugin, task -> discover(player), null);
                }
            }, 1, DISCOVERY_PERIOD_TICKS);
            plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> {
                if (!settings.showSelectionBorders) {
                    return;
                }
                for (UUID playerId : activeSelections.keySet()) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null) {
                        activeSelections.remove(playerId);
                    } else {
                        player.getScheduler().run(plugin, task -> renderCached(player), null);
                    }
                }
            }, updatePeriodTicks, updatePeriodTicks);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                refreshSettings();
                if (!shouldProcessSelections()) {
                    return;
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    discover(player);
                }
            }, 1, DISCOVERY_PERIOD_TICKS);
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!settings.showSelectionBorders) {
                    return;
                }
                for (UUID playerId : activeSelections.keySet()) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null) {
                        activeSelections.remove(playerId);
                    } else {
                        renderCached(player);
                    }
                }
            }, updatePeriodTicks, updatePeriodTicks);
        }
    }

    private boolean shouldProcessSelections() {
        if (settings.showSelectionBorders || settings.selectionMaximumLifetimeSeconds >= 0
                || settings.selectionLimit != null && settings.selectionLimit.enabled) {
            return true;
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            if (settings.get(BukkitAdapter.adapt(world)).claimExpansionOfferMode.selection) {
                return true;
            }
        }
        return false;
    }

    private void discover(Player player) {
        if (!player.isOnline()) {
            activeSelections.remove(player.getUniqueId());
            return;
        }

        LocalPlayer actor = plugin.wrapPlayer(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(actor);
        if (session == null) {
            activeSelections.remove(player.getUniqueId());
            return;
        }

        World selectionWorld = session.getSelectionWorld();
        if (selectionWorld == null || !selectionWorld.getName().equals(player.getWorld().getName())) {
            activeSelections.remove(player.getUniqueId());
            return;
        }

        try {
            Region selection = session.getRegionSelector(selectionWorld).getRegion();
            SelectionShape shape = SelectionShape.capture(selectionWorld, selection);
            CachedSelection cached = activeSelections.get(player.getUniqueId());
            boolean manualExpansionDisabled = plugin.isClaimExpansionDisabled(player.getUniqueId());
            boolean expansionDisabled = manualExpansionDisabled;
            if (cached != null && cached.shape.equals(shape)
                    && cached.manualExpansionDisabled == manualExpansionDisabled
                    && cached.selectionLimit == settings.selectionLimit) {
                return;
            }
            boolean newSelection = cached == null || !cached.shape.equals(shape);
            SelectionExpiry expiry = SelectionExpiry.capture(actor, selectionWorld);
            expiry.schedule(settings.selectionMaximumLifetimeSeconds);
            Outline outline = createOutline(actor, selectionWorld, selection, expansionDisabled);
            SelectionLimitTracker.Decision limitDecision = plugin.getSelectionLimitTracker()
                    .evaluate(actor, selectionWorld, outline.originalRegion, outline.expandedVolume);
            if (limitDecision == SelectionLimitTracker.Decision.DENY) {
                expiry.clear();
                activeSelections.remove(player.getUniqueId());
                plugin.getSelectionLimitTracker().forget(player.getUniqueId());
                plugin.getMessages().send(player, com.sk89q.worldguard.bukkit.BukkitMessages.template(
                        "selectionLimitExceeded",
                        "maximum", settings.selectionLimit.maximumVolume,
                        "current", outline.originalVolume));
                return;
            }
            if (limitDecision == SelectionLimitTracker.Decision.WITHOUT_EXPANSION) {
                expansionDisabled = true;
                plugin.getMessages().send(player, com.sk89q.worldguard.bukkit.BukkitMessages.template(
                        "selectionLimitExpansionSkipped",
                        "maximum", settings.selectionLimit.maximumVolume,
                        "expanded", outline.expandedVolume));
                outline = createOutline(actor, selectionWorld, selection, true);
            } else if (limitDecision == SelectionLimitTracker.Decision.CONFIRM
                    && plugin.getSelectionLimitTracker().markPrompted(
                            actor, selectionWorld, outline.originalRegion)) {
                plugin.getMessages().send(player, com.sk89q.worldguard.bukkit.BukkitMessages.template(
                        "selectionLimitConfirmationRequired",
                        "maximum", settings.selectionLimit.maximumVolume,
                        "current", outline.originalVolume));
            }
            activeSelections.put(player.getUniqueId(), new CachedSelection(
                    shape, outline.lines, manualExpansionDisabled, settings.selectionLimit));
            if (newSelection && outline.offerRemoval && outline.expandedVolume != outline.originalVolume) {
                plugin.getMessages().send(player, com.sk89q.worldguard.bukkit.BukkitMessages.template(
                        "claimExpansionSelection",
                        "original", outline.originalVolume,
                        "expanded", outline.expandedVolume));
            }
        } catch (IncompleteRegionException ignored) {
            activeSelections.remove(player.getUniqueId());
            // An incomplete selection has no border to display.
        }
    }

    private void renderCached(Player player) {
        if (!player.isOnline()) {
            activeSelections.remove(player.getUniqueId());
            return;
        }
        CachedSelection cached = activeSelections.get(player.getUniqueId());
        if (cached == null || !cached.shape.worldName.equals(player.getWorld().getName())) {
            return;
        }
        renderLines(player, cached);
    }

    private Outline createOutline(LocalPlayer player, World world, Region selection,
                                  boolean expansionDisabled) {
        WorldConfiguration configuration = WorldGuard.getInstance()
                .getPlatform().getGlobalStateManager().get(world);
        if (selection instanceof CuboidRegion) {
            ProtectedCuboidRegion original = new ProtectedCuboidRegion(
                    "selection_preview", selection.getMinimumPoint(), selection.getMaximumPoint());
            ProtectedCuboidRegion preview = original;
            int originalVolume = original.volume();
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(world);
            if (manager != null && !expansionDisabled) {
                preview = ClaimRegionExpander.expand(
                        preview,
                        world.getMinimumPoint(),
                        world.getMaximumPoint(),
                        configuration.claimExpansion,
                        configuration.getMaxClaimVolume(player),
                        candidate -> manager.getApplicableRegions(candidate).isOwnerOfAll(player));
            }
            List<Line> lines = switch (particleSettings().mode) {
                case OUTLINE -> createCuboidOutline(preview.getMinimumPoint(), preview.getMaximumPoint());
                case CHUNKS -> createChunkGridOutline(preview.getMinimumPoint(), preview.getMaximumPoint());
                case CHUNK_BORDERS -> createChunkBorderOutline(
                        preview.getMinimumPoint(), preview.getMaximumPoint());
            };
            return new Outline(
                    lines,
                    originalVolume, preview.volume(),
                    configuration.claimExpansionOfferMode.selection && !expansionDisabled,
                    original);
        }
        if (selection instanceof Polygonal2DRegion polygon) {
            ProtectedPolygonalRegion original = new ProtectedPolygonalRegion(
                    "selection_preview",
                    polygon.getPoints(),
                    polygon.getMinimumPoint().y(),
                    polygon.getMaximumPoint().y());
            ProtectedPolygonalRegion preview = original;
            int originalVolume = original.volume();
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(world);
            if (manager != null && !expansionDisabled) {
                preview = ClaimRegionExpander.expand(
                        preview,
                        world.getMinimumPoint(),
                        world.getMaximumPoint(),
                        configuration.claimExpansion,
                        configuration.getMaxClaimVolume(player),
                        candidate -> manager.getApplicableRegions(candidate).isOwnerOfAll(player));
            }
            List<Line> lines = switch (particleSettings().mode) {
                case OUTLINE -> createPolygonOutline(
                        preview.getPoints(),
                        preview.getMinimumPoint().y(),
                        preview.getMaximumPoint().y());
                case CHUNKS -> createPolygonChunkOutline(polygon, preview);
                case CHUNK_BORDERS -> createPolygonChunkBorderOutline(polygon, preview);
            };
            return new Outline(
                    lines,
                    originalVolume, preview.volume(),
                    configuration.claimExpansionOfferMode.selection && !expansionDisabled,
                    original);
        }
        return new Outline(List.of(), 0, 0, false,
                new ProtectedCuboidRegion(
                        "selection_preview", selection.getMinimumPoint(), selection.getMaximumPoint()));
    }

    private static List<Line> createCuboidOutline(BlockVector3 minimum, BlockVector3 maximum) {
        double minX = minimum.x();
        double minY = minimum.y();
        double minZ = minimum.z();
        double maxX = maximum.x() + 1.0;
        double maxY = maximum.y() + 1.0;
        double maxZ = maximum.z() + 1.0;
        List<Line> lines = new ArrayList<>(12);

        addLine(lines, minX, minY, minZ, maxX, minY, minZ);
        addLine(lines, minX, minY, maxZ, maxX, minY, maxZ);
        addLine(lines, minX, maxY, minZ, maxX, maxY, minZ);
        addLine(lines, minX, maxY, maxZ, maxX, maxY, maxZ);

        addLine(lines, minX, minY, minZ, minX, minY, maxZ);
        addLine(lines, maxX, minY, minZ, maxX, minY, maxZ);
        addLine(lines, minX, maxY, minZ, minX, maxY, maxZ);
        addLine(lines, maxX, maxY, minZ, maxX, maxY, maxZ);

        addLine(lines, minX, minY, minZ, minX, maxY, minZ);
        addLine(lines, maxX, minY, minZ, maxX, maxY, minZ);
        addLine(lines, minX, minY, maxZ, minX, maxY, maxZ);
        addLine(lines, maxX, minY, maxZ, maxX, maxY, maxZ);
        return lines;
    }

    private static List<Line> createChunkGridOutline(BlockVector3 minimum, BlockVector3 maximum) {
        int minimumChunkX = Math.floorDiv(minimum.x(), 16);
        int maximumChunkX = Math.floorDiv(maximum.x(), 16);
        int minimumChunkZ = Math.floorDiv(minimum.z(), 16);
        int maximumChunkZ = Math.floorDiv(maximum.z(), 16);
        long chunkCount = ((long) maximumChunkX - minimumChunkX + 1)
                * ((long) maximumChunkZ - minimumChunkZ + 1);
        if (chunkCount > MAX_CHUNK_CUBES) {
            return createCuboidOutline(minimum, maximum);
        }

        List<Integer> xBoundaries = chunkBoundaries(minimum.x(), maximum.x(), minimumChunkX, maximumChunkX);
        List<Integer> zBoundaries = chunkBoundaries(minimum.z(), maximum.z(), minimumChunkZ, maximumChunkZ);
        double minimumY = minimum.y();
        double maximumY = maximum.y() + 1.0;
        double minimumX = minimum.x();
        double maximumX = maximum.x() + 1.0;
        double minimumZ = minimum.z();
        double maximumZ = maximum.z() + 1.0;
        List<Line> lines = new ArrayList<>(
                xBoundaries.size() * zBoundaries.size()
                        + (xBoundaries.size() + zBoundaries.size()) * 2);

        for (int z : zBoundaries) {
            addLine(lines, minimumX, minimumY, z, maximumX, minimumY, z);
            addLine(lines, minimumX, maximumY, z, maximumX, maximumY, z);
        }
        for (int x : xBoundaries) {
            addLine(lines, x, minimumY, minimumZ, x, minimumY, maximumZ);
            addLine(lines, x, maximumY, minimumZ, x, maximumY, maximumZ);
            for (int z : zBoundaries) {
                addLine(lines, x, minimumY, z, x, maximumY, z);
            }
        }
        return lines;
    }

    private static List<Integer> chunkBoundaries(
            int minimum, int maximum, int minimumChunk, int maximumChunk) {
        List<Integer> boundaries = new ArrayList<>(maximumChunk - minimumChunk + 2);
        boundaries.add(minimum);
        for (int chunk = minimumChunk + 1; chunk <= maximumChunk; chunk++) {
            boundaries.add(chunk << 4);
        }
        int end = maximum + 1;
        if (boundaries.getLast() != end) {
            boundaries.add(end);
        }
        return boundaries;
    }

    private static List<Line> createChunkBorderOutline(BlockVector3 minimum, BlockVector3 maximum) {
        int minimumChunkX = Math.floorDiv(minimum.x(), 16);
        int maximumChunkX = Math.floorDiv(maximum.x(), 16);
        int minimumChunkZ = Math.floorDiv(minimum.z(), 16);
        int maximumChunkZ = Math.floorDiv(maximum.z(), 16);
        long chunkCount = ((long) maximumChunkX - minimumChunkX + 1)
                * ((long) maximumChunkZ - minimumChunkZ + 1);
        if (chunkCount > MAX_CHUNK_CUBES) {
            return createCuboidOutline(
                    BlockVector3.at(minimumChunkX << 4, minimum.y(), minimumChunkZ << 4),
                    BlockVector3.at(((maximumChunkX + 1) << 4) - 1, maximum.y(),
                            ((maximumChunkZ + 1) << 4) - 1));
        }

        int chunkWidth = maximumChunkX - minimumChunkX + 1;
        int chunkLength = maximumChunkZ - minimumChunkZ + 1;
        int perimeterChunks = chunkWidth == 1 ? chunkLength
                : chunkLength == 1 ? chunkWidth : 2 * chunkWidth + 2 * chunkLength - 4;
        Set<Edge> edges = new LinkedHashSet<>(Math.max(16, perimeterChunks * 8));
        int minimumY = minimum.y();
        int maximumY = maximum.y() + 1;
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                if (chunkX != minimumChunkX && chunkX != maximumChunkX
                        && chunkZ != minimumChunkZ && chunkZ != maximumChunkZ) {
                    continue;
                }
                int minimumX = chunkX << 4;
                int minimumZ = chunkZ << 4;
                addCuboidEdges(edges, minimumX, minimumY, minimumZ,
                        minimumX + 16, maximumY, minimumZ + 16);
            }
        }
        return linesFromEdges(edges);
    }

    private static List<Line> createPolygonChunkOutline(
            Polygonal2DRegion polygon, ProtectedPolygonalRegion preview) {
        BlockVector3 minimum = preview.getMinimumPoint();
        BlockVector3 maximum = preview.getMaximumPoint();
        long boundingChunks = ((long) Math.floorDiv(maximum.x(), 16) - Math.floorDiv(minimum.x(), 16) + 1)
                * ((long) Math.floorDiv(maximum.z(), 16) - Math.floorDiv(minimum.z(), 16) + 1);
        if (boundingChunks > MAX_CHUNK_CUBES) {
            return createPolygonOutline(preview.getPoints(), minimum.y(), maximum.y());
        }
        Collection<BlockVector2> chunks = polygon.getChunks();
        if (chunks.size() > MAX_CHUNK_CUBES) {
            return createPolygonOutline(preview.getPoints(), minimum.y(), maximum.y());
        }
        Set<Edge> edges = new LinkedHashSet<>(Math.max(16, chunks.size() * 8));
        int minimumY = minimum.y();
        int maximumY = maximum.y() + 1;
        for (BlockVector2 chunk : chunks) {
            int minimumX = chunk.x() << 4;
            int minimumZ = chunk.z() << 4;
            addCuboidEdges(edges, minimumX, minimumY, minimumZ,
                    minimumX + 16, maximumY, minimumZ + 16);
        }
        return linesFromEdges(edges);
    }

    private static List<Line> createPolygonChunkBorderOutline(
            Polygonal2DRegion polygon, ProtectedPolygonalRegion preview) {
        BlockVector3 minimum = preview.getMinimumPoint();
        BlockVector3 maximum = preview.getMaximumPoint();
        Collection<BlockVector2> chunks = polygon.getChunks();
        if (chunks.size() > MAX_CHUNK_CUBES) {
            return createChunkBorderOutline(minimum, maximum);
        }
        Set<BlockVector2> chunkSet = new HashSet<>(chunks);
        Set<Edge> edges = new LinkedHashSet<>(Math.max(16, chunks.size() * 8));
        int minimumY = minimum.y();
        int maximumY = maximum.y() + 1;
        for (BlockVector2 chunk : chunks) {
            int chunkX = chunk.x();
            int chunkZ = chunk.z();
            if (chunkSet.contains(BlockVector2.at(chunkX - 1, chunkZ))
                    && chunkSet.contains(BlockVector2.at(chunkX + 1, chunkZ))
                    && chunkSet.contains(BlockVector2.at(chunkX, chunkZ - 1))
                    && chunkSet.contains(BlockVector2.at(chunkX, chunkZ + 1))) {
                continue;
            }
            int minimumX = chunk.x() << 4;
            int minimumZ = chunk.z() << 4;
            addCuboidEdges(edges, minimumX, minimumY, minimumZ,
                    minimumX + 16, maximumY, minimumZ + 16);
        }
        return linesFromEdges(edges);
    }

    private static List<Line> linesFromEdges(Set<Edge> edges) {
        List<Line> lines = new ArrayList<>(edges.size());
        for (Edge edge : edges) {
            addLine(lines, edge.startX, edge.startY, edge.startZ,
                    edge.endX, edge.endY, edge.endZ);
        }
        return lines;
    }

    private static void addCuboidEdges(Set<Edge> edges,
                                       int minX, int minY, int minZ,
                                       int maxX, int maxY, int maxZ) {
        edges.add(new Edge(minX, minY, minZ, maxX, minY, minZ));
        edges.add(new Edge(minX, minY, maxZ, maxX, minY, maxZ));
        edges.add(new Edge(minX, maxY, minZ, maxX, maxY, minZ));
        edges.add(new Edge(minX, maxY, maxZ, maxX, maxY, maxZ));
        edges.add(new Edge(minX, minY, minZ, minX, minY, maxZ));
        edges.add(new Edge(maxX, minY, minZ, maxX, minY, maxZ));
        edges.add(new Edge(minX, maxY, minZ, minX, maxY, maxZ));
        edges.add(new Edge(maxX, maxY, minZ, maxX, maxY, maxZ));
        edges.add(new Edge(minX, minY, minZ, minX, maxY, minZ));
        edges.add(new Edge(maxX, minY, minZ, maxX, maxY, minZ));
        edges.add(new Edge(minX, minY, maxZ, minX, maxY, maxZ));
        edges.add(new Edge(maxX, minY, maxZ, maxX, maxY, maxZ));
    }

    private static List<Line> createPolygonOutline(
            List<BlockVector2> polygonPoints, int minimumY, int maximumY) {
        List<BlockVector2> points = sampleVertices(polygonPoints);
        if (points.size() < 2) {
            return List.of();
        }

        double maxY = maximumY + 1.0;
        List<Line> lines = new ArrayList<>(points.size() * 3);
        for (int i = 0; i < points.size(); i++) {
            BlockVector2 point = points.get(i);
            BlockVector2 next = points.get((i + 1) % points.size());
            double x = point.x() + 0.5;
            double z = point.z() + 0.5;
            double nextX = next.x() + 0.5;
            double nextZ = next.z() + 0.5;
            addLine(lines, x, minimumY, z, nextX, minimumY, nextZ);
            addLine(lines, x, maxY, z, nextX, maxY, nextZ);
            addLine(lines, x, minimumY, z, x, maxY, z);
        }
        return lines;
    }

    private static List<BlockVector2> sampleVertices(List<BlockVector2> points) {
        if (points.size() <= MAX_POLYGON_VERTICES) {
            return points;
        }
        List<BlockVector2> sampled = new ArrayList<>(MAX_POLYGON_VERTICES);
        for (int i = 0; i < MAX_POLYGON_VERTICES; i++) {
            sampled.add(points.get(i * points.size() / MAX_POLYGON_VERTICES));
        }
        return sampled;
    }

    private static void addLine(List<Line> lines,
                                double startX, double startY, double startZ,
                                double endX, double endY, double endZ) {
        lines.add(new Line(startX, startY, startZ, endX, endY, endZ));
    }

    private void renderLines(Player player, CachedSelection cached) {
        List<Line> lines = cached.lines;
        if (lines.isEmpty()) {
            return;
        }

        ParticleSettings particles = particleSettings();
        int maxParticles = particles.maxCount;
        double maxDistanceSquared = particles.maxDistanceSquared;
        Location eye = player.getEyeLocation();
        double[] visibility = cached.visibility;
        int visibleCount = 0;
        double totalVisibleLength = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            int offset = i * 2;
            if (visiblePart(line, eye, maxDistanceSquared, visibility, offset)) {
                visibleCount++;
                totalVisibleLength += line.length
                        * (visibility[offset + 1] - visibility[offset]);
            }
        }
        if (visibleCount == 0) {
            return;
        }

        if (visibleCount >= maxParticles) {
            int visibleIndex = 0;
            int emitted = 0;
            for (int i = 0; i < lines.size() && emitted < maxParticles; i++) {
                int offset = i * 2;
                if (Double.isNaN(visibility[offset])) continue;
                int target = emitted * visibleCount / maxParticles;
                if (visibleIndex++ < target) continue;
                emitParticle(player, particles, lines.get(i),
                        visibility[offset], visibility[offset + 1], 0.5);
                emitted++;
            }
            return;
        }

        int extraPointBudget = Math.max(1, maxParticles - visibleCount);
        double spacing = Math.max(particles.spacing, totalVisibleLength / extraPointBudget);
        int emitted = 0;

        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            int offset = lineIndex * 2;
            if (Double.isNaN(visibility[offset])) continue;
            Line line = lines.get(lineIndex);
            double visibleLength = line.length * (visibility[offset + 1] - visibility[offset]);
            int points = 1 + (int) Math.floor(visibleLength / spacing);
            for (int i = 0; i < points && emitted < maxParticles; i++) {
                emitParticle(player, particles, line, visibility[offset], visibility[offset + 1],
                        points == 1 ? 0.5 : (double) i / (points - 1));
                emitted++;
            }
            if (emitted >= maxParticles) {
                return;
            }
        }
    }

    private static void emitParticle(
            Player player, ParticleSettings particles, Line line,
            double startProgress, double endProgress, double lineProgress) {
        double progress = startProgress + (endProgress - startProgress) * lineProgress;
        double x = line.startX + (line.endX - line.startX) * progress;
        double y = line.startY + (line.endY - line.startY) * progress;
        double z = line.startZ + (line.endZ - line.startZ) * progress;
        player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, particles.data);
    }

    private ParticleSettings particleSettings() {
        return particleSettings;
    }

    private void refreshSettings() {
        ParticleSettings cached = particleSettings;
        if (!cached.matches(settings)) {
            ParticleSettings replacement = ParticleSettings.from(settings);
            particleSettings = replacement;
            if (cached.mode != replacement.mode) {
                activeSelections.clear();
            }
        }
    }

    private static boolean visiblePart(
            Line line, Location eye, double maxDistanceSquared, double[] visibility, int offset) {
        if (line.length == 0) {
            double deltaX = eye.getX() - line.startX;
            double deltaY = eye.getY() - line.startY;
            double deltaZ = eye.getZ() - line.startZ;
            boolean visible = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= maxDistanceSquared;
            visibility[offset] = visible ? 0 : Double.NaN;
            visibility[offset + 1] = 0;
            return visible;
        }

        double vectorX = line.endX - line.startX;
        double vectorY = line.endY - line.startY;
        double vectorZ = line.endZ - line.startZ;
        double fromStartX = eye.getX() - line.startX;
        double fromStartY = eye.getY() - line.startY;
        double fromStartZ = eye.getZ() - line.startZ;
        double lengthSquared = line.length * line.length;
        double projectedProgress =
                (fromStartX * vectorX + fromStartY * vectorY + fromStartZ * vectorZ) / lengthSquared;
        double closestX = line.startX + vectorX * projectedProgress;
        double closestY = line.startY + vectorY * projectedProgress;
        double closestZ = line.startZ + vectorZ * projectedProgress;
        double closestDeltaX = eye.getX() - closestX;
        double closestDeltaY = eye.getY() - closestY;
        double closestDeltaZ = eye.getZ() - closestZ;
        double closestDistanceSquared = closestDeltaX * closestDeltaX
                + closestDeltaY * closestDeltaY + closestDeltaZ * closestDeltaZ;
        if (closestDistanceSquared > maxDistanceSquared) {
            visibility[offset] = Double.NaN;
            return false;
        }

        double visibleProgress = Math.sqrt(maxDistanceSquared - closestDistanceSquared) / line.length;
        double startProgress = Math.max(0, projectedProgress - visibleProgress);
        double endProgress = Math.min(1, projectedProgress + visibleProgress);
        if (startProgress > endProgress) {
            visibility[offset] = Double.NaN;
            return false;
        }
        visibility[offset] = startProgress;
        visibility[offset + 1] = endProgress;
        return true;
    }

    private static final class Line {
        final double startX;
        final double startY;
        final double startZ;
        final double endX;
        final double endY;
        final double endZ;
        final double length;

        Line(double startX, double startY, double startZ,
             double endX, double endY, double endZ) {
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.endX = endX;
            this.endY = endY;
            this.endZ = endZ;
            double deltaX = endX - startX;
            double deltaY = endY - startY;
            double deltaZ = endZ - startZ;
            this.length = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        }
    }

    private static final class Edge {
        public final int startX;
        public final int startY;
        public final int startZ;
        public final int endX;
        public final int endY;
        public final int endZ;
        public final int hashCode;

        private Edge(int firstX, int firstY, int firstZ, int secondX, int secondY, int secondZ) {
            boolean ordered = firstX < secondX
                    || firstX == secondX && (firstY < secondY
                    || firstY == secondY && firstZ <= secondZ);
            startX = ordered ? firstX : secondX;
            startY = ordered ? firstY : secondY;
            startZ = ordered ? firstZ : secondZ;
            endX = ordered ? secondX : firstX;
            endY = ordered ? secondY : firstY;
            endZ = ordered ? secondZ : firstZ;
            int hash = startX;
            hash = 31 * hash + startY;
            hash = 31 * hash + startZ;
            hash = 31 * hash + endX;
            hash = 31 * hash + endY;
            hashCode = 31 * hash + endZ;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Edge edge)) return false;
            return startX == edge.startX && startY == edge.startY && startZ == edge.startZ
                    && endX == edge.endX && endY == edge.endY && endZ == edge.endZ;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class CachedSelection {
        public final SelectionShape shape;
        public final List<Line> lines;
        public final double[] visibility;
        public final boolean manualExpansionDisabled;
        public final SelectionLimit selectionLimit;

        private CachedSelection(SelectionShape shape, List<Line> lines, boolean manualExpansionDisabled,
                                SelectionLimit selectionLimit) {
            this.shape = shape;
            this.lines = List.copyOf(lines);
            this.visibility = new double[lines.size() * 2];
            this.manualExpansionDisabled = manualExpansionDisabled;
            this.selectionLimit = selectionLimit;
        }
    }

    private static final class Outline {
        public final List<Line> lines;
        public final int originalVolume;
        public final int expandedVolume;
        public final boolean offerRemoval;
        public final ProtectedRegion originalRegion;

        private Outline(List<Line> lines, int originalVolume, int expandedVolume,
                        boolean offerRemoval, ProtectedRegion originalRegion) {
            this.lines = lines;
            this.originalVolume = originalVolume;
            this.expandedVolume = expandedVolume;
            this.offerRemoval = offerRemoval;
            this.originalRegion = originalRegion;
        }
    }

    private static final class SelectionShape {
        public final String worldName;
        public final BlockVector3 minimum;
        public final BlockVector3 maximum;
        public final List<BlockVector2> polygonPoints;

        private SelectionShape(String worldName, BlockVector3 minimum, BlockVector3 maximum,
                               List<BlockVector2> polygonPoints) {
            this.worldName = worldName;
            this.minimum = minimum;
            this.maximum = maximum;
            this.polygonPoints = polygonPoints;
        }

        private static SelectionShape capture(World world, Region region) {
            List<BlockVector2> points = region instanceof Polygonal2DRegion polygon
                    ? List.copyOf(polygon.getPoints()) : List.of();
            return new SelectionShape(
                    world.getName(), region.getMinimumPoint(), region.getMaximumPoint(), points);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SelectionShape shape)) {
                return false;
            }
            return worldName.equals(shape.worldName)
                    && minimum.equals(shape.minimum)
                    && maximum.equals(shape.maximum)
                    && polygonPoints.equals(shape.polygonPoints);
        }

        @Override
        public int hashCode() {
            int result = worldName.hashCode();
            result = 31 * result + minimum.hashCode();
            result = 31 * result + maximum.hashCode();
            return 31 * result + polygonPoints.hashCode();
        }
    }

    private static final class ParticleSettings {
        public final float size;
        public final double spacing;
        public final int maxCount;
        public final double viewDistance;
        public final double maxDistanceSquared;
        public final int red;
        public final int green;
        public final int blue;
        public final SelectionParticleMode mode;
        public final Particle.DustOptions data;

        private ParticleSettings(float size, double spacing, int maxCount, double viewDistance,
                                 int red, int green, int blue, SelectionParticleMode mode) {
            this.size = size;
            this.spacing = spacing;
            this.maxCount = maxCount;
            this.viewDistance = viewDistance;
            this.maxDistanceSquared = viewDistance * viewDistance;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.mode = mode;
            this.data = new Particle.DustOptions(Color.fromRGB(red, green, blue), size);
        }

        static ParticleSettings from(ConfigurationManager settings) {
            return new ParticleSettings(
                    settings.selectionParticleSize,
                    settings.selectionParticleSpacing,
                    settings.selectionParticleMaxCount,
                    settings.selectionParticleViewDistance,
                    settings.selectionParticleRed,
                    settings.selectionParticleGreen,
                    settings.selectionParticleBlue,
                    settings.selectionParticleMode);
        }

        boolean matches(ConfigurationManager settings) {
            return Float.compare(size, settings.selectionParticleSize) == 0
                    && Double.compare(spacing, settings.selectionParticleSpacing) == 0
                    && maxCount == settings.selectionParticleMaxCount
                    && Double.compare(viewDistance, settings.selectionParticleViewDistance) == 0
                    && red == settings.selectionParticleRed
                    && green == settings.selectionParticleGreen
                    && blue == settings.selectionParticleBlue
                    && mode == settings.selectionParticleMode;
        }
    }
}
