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
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Draws completed WorldEdit selections for their owners with client-side particles.
 */
public final class SelectionVisualizer {

    private static final int MAX_POLYGON_VERTICES = 24;
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
        return settings.showSelectionBorders || settings.selectionMaximumLifetimeSeconds >= 0;
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
            if (cached != null && cached.shape.equals(shape)) {
                return;
            }
            SelectionExpiry expiry = SelectionExpiry.capture(actor, selectionWorld);
            expiry.schedule(settings.selectionMaximumLifetimeSeconds);
            activeSelections.put(player.getUniqueId(), new CachedSelection(
                    shape, createOutline(actor, selectionWorld, selection)));
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
        renderLines(player, cached.lines);
    }

    private List<Line> createOutline(LocalPlayer player, World world, Region selection) {
        if (selection instanceof CuboidRegion) {
            ProtectedCuboidRegion preview = new ProtectedCuboidRegion(
                    "selection_preview", selection.getMinimumPoint(), selection.getMaximumPoint());
            WorldConfiguration configuration = WorldGuard.getInstance()
                    .getPlatform().getGlobalStateManager().get(world);
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(world);
            if (manager != null) {
                preview = ClaimRegionExpander.expand(
                        preview,
                        world.getMinimumPoint(),
                        world.getMaximumPoint(),
                        configuration.claimExpansion,
                        configuration.getMaxClaimVolume(player),
                        candidate -> manager.getApplicableRegions(candidate).isOwnerOfAll(player));
            }
            return createCuboidOutline(preview.getMinimumPoint(), preview.getMaximumPoint());
        }
        if (selection instanceof Polygonal2DRegion polygon) {
            ProtectedPolygonalRegion preview = new ProtectedPolygonalRegion(
                    "selection_preview",
                    polygon.getPoints(),
                    polygon.getMinimumPoint().y(),
                    polygon.getMaximumPoint().y());
            WorldConfiguration configuration = WorldGuard.getInstance()
                    .getPlatform().getGlobalStateManager().get(world);
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(world);
            if (manager != null) {
                preview = ClaimRegionExpander.expand(
                        preview,
                        world.getMinimumPoint(),
                        world.getMaximumPoint(),
                        configuration.claimExpansion,
                        configuration.getMaxClaimVolume(player),
                        candidate -> manager.getApplicableRegions(candidate).isOwnerOfAll(player));
            }
            return createPolygonOutline(
                    preview.getPoints(),
                    preview.getMinimumPoint().y(),
                    preview.getMaximumPoint().y());
        }
        return List.of();
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

    private static List<Line> createPolygonOutline(
            List<BlockVector2> polygonPoints, int minimumY, int maximumY) {
        List<BlockVector2> points = sampleVertices(polygonPoints);
        if (points.size() < 2) {
            return List.of();
        }

        double minY = minimumY;
        double maxY = maximumY + 1.0;
        List<Line> lines = new ArrayList<>(points.size() * 3);
        for (int i = 0; i < points.size(); i++) {
            BlockVector2 point = points.get(i);
            BlockVector2 next = points.get((i + 1) % points.size());
            double x = point.x() + 0.5;
            double z = point.z() + 0.5;
            double nextX = next.x() + 0.5;
            double nextZ = next.z() + 0.5;
            addLine(lines, x, minY, z, nextX, minY, nextZ);
            addLine(lines, x, maxY, z, nextX, maxY, nextZ);
            addLine(lines, x, minY, z, x, maxY, z);
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

    private void renderLines(Player player, List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }

        ParticleSettings particles = particleSettings();
        int maxParticles = particles.maxCount;
        double maxDistanceSquared = particles.maxDistanceSquared;
        Location eye = player.getEyeLocation();
        List<VisibleLine> visibleLines = new ArrayList<>(lines.size());
        double totalVisibleLength = 0;
        for (Line line : lines) {
            VisibleLine visible = visiblePart(line, eye, maxDistanceSquared);
            if (visible != null) {
                visibleLines.add(visible);
                totalVisibleLength += visible.length;
            }
        }
        if (visibleLines.isEmpty()) {
            return;
        }

        int extraPointBudget = Math.max(1, maxParticles - visibleLines.size() * 2);
        double spacing = Math.max(particles.spacing, totalVisibleLength / extraPointBudget);
        int emitted = 0;

        for (VisibleLine visible : visibleLines) {
            Line line = visible.line;
            int segments = Math.max(1, (int) Math.floor(visible.length / spacing));
            for (int i = 0; i <= segments && emitted < maxParticles; i++) {
                double progress = visible.startProgress
                        + (visible.endProgress - visible.startProgress) * i / segments;
                double x = line.startX + (line.endX - line.startX) * progress;
                double y = line.startY + (line.endY - line.startY) * progress;
                double z = line.startZ + (line.endZ - line.startZ) * progress;
                player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, particles.data);
                emitted++;
            }
            if (emitted >= maxParticles) {
                return;
            }
        }
    }

    private ParticleSettings particleSettings() {
        ParticleSettings cached = particleSettings;
        if (cached.matches(settings)) {
            return cached;
        }
        synchronized (this) {
            cached = particleSettings;
            if (!cached.matches(settings)) {
                cached = ParticleSettings.from(settings);
                particleSettings = cached;
            }
            return cached;
        }
    }

    private static VisibleLine visiblePart(Line line, Location eye, double maxDistanceSquared) {
        if (line.length == 0) {
            double deltaX = eye.getX() - line.startX;
            double deltaY = eye.getY() - line.startY;
            double deltaZ = eye.getZ() - line.startZ;
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= maxDistanceSquared
                    ? new VisibleLine(line, 0, 0) : null;
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
            return null;
        }

        double visibleProgress = Math.sqrt(maxDistanceSquared - closestDistanceSquared) / line.length;
        double startProgress = Math.max(0, projectedProgress - visibleProgress);
        double endProgress = Math.min(1, projectedProgress + visibleProgress);
        return startProgress <= endProgress ? new VisibleLine(line, startProgress, endProgress) : null;
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

    private static final class VisibleLine {
        final Line line;
        final double startProgress;
        final double endProgress;
        final double length;

        VisibleLine(Line line, double startProgress, double endProgress) {
            this.line = line;
            this.startProgress = startProgress;
            this.endProgress = endProgress;
            this.length = line.length * (endProgress - startProgress);
        }
    }

    private static final class CachedSelection {
        public final SelectionShape shape;
        public final List<Line> lines;

        private CachedSelection(SelectionShape shape, List<Line> lines) {
            this.shape = shape;
            this.lines = List.copyOf(lines);
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
        public final Particle.DustOptions data;

        private ParticleSettings(float size, double spacing, int maxCount, double viewDistance,
                                 int red, int green, int blue) {
            this.size = size;
            this.spacing = spacing;
            this.maxCount = maxCount;
            this.viewDistance = viewDistance;
            this.maxDistanceSquared = viewDistance * viewDistance;
            this.red = red;
            this.green = green;
            this.blue = blue;
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
                    settings.selectionParticleBlue);
        }

        boolean matches(ConfigurationManager settings) {
            return Float.compare(size, settings.selectionParticleSize) == 0
                    && Double.compare(spacing, settings.selectionParticleSpacing) == 0
                    && maxCount == settings.selectionParticleMaxCount
                    && Double.compare(viewDistance, settings.selectionParticleViewDistance) == 0
                    && red == settings.selectionParticleRed
                    && green == settings.selectionParticleGreen
                    && blue == settings.selectionParticleBlue;
        }
    }
}
