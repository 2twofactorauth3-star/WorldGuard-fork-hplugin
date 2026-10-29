package com.sk89q.worldguard.protection.managers.migration;

import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.storage.RegionDatabase;
import com.sk89q.worldguard.protection.managers.storage.RegionDriver;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion.CircularInheritanceException;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;

public class WorldHeightMigration extends AbstractMigration {
    private final FlagRegistry flags;
    private final World world;

    public WorldHeightMigration(RegionDriver driver, FlagRegistry flags, @Nullable World world) {
        super(driver);
        this.flags = flags;
        this.world = world;
    }

    @Override
    protected void migrate(RegionDatabase store) throws MigrationException {
        if (world != null && !world.getName().equals(store.getName())) return;
        World target = world;
        var platform = WorldGuard.getInstance().getPlatformIfPresent();
        if (target == null && platform != null) target = platform.getMatcher().getWorldByName(store.getName());
        int minimum = target == null ? -64 : target.getMinY();
        int maximum = target == null ? 319 : target.getMaxY();
        if (minimum == 0 && maximum == 255) return;
        try {
            Map<String, ProtectedRegion> migrated = new HashMap<>();
            for (ProtectedRegion region : store.loadAll(flags)) {
                migrated.put(region.getId(), expand(region, minimum, maximum));
            }
            for (ProtectedRegion region : migrated.values()) {
                ProtectedRegion parent = region.getParent();
                if (parent != null && migrated.containsKey(parent.getId())) region.setParent(migrated.get(parent.getId()));
            }
            store.saveAll(new HashSet<>(migrated.values()));
        } catch (StorageException | CircularInheritanceException error) {
            throw new MigrationException("Height migration failed for world '" + store.getName() + "'", error);
        }
    }

    private static ProtectedRegion expand(ProtectedRegion region, int minimum, int maximum) throws MigrationException {
        if (!region.isPhysicalArea() || region.getMinimumPoint().y() > 0 || region.getMaximumPoint().y() < 255) return region;
        ProtectedRegion expanded;
        if (region instanceof ProtectedCuboidRegion) {
            expanded = new ProtectedCuboidRegion(region.getId(), region.isTransient(),
                    region.getMinimumPoint().withY(minimum), region.getMaximumPoint().withY(maximum));
        } else if (region instanceof ProtectedPolygonalRegion) {
            expanded = new ProtectedPolygonalRegion(region.getId(), region.isTransient(), region.getPoints(), minimum, maximum);
        } else {
            throw new MigrationException("Unsupported region type: " + region.getClass().getName());
        }
        expanded.copyFrom(region);
        return expanded;
    }

    @Override
    protected void postMigration() {
    }
}
