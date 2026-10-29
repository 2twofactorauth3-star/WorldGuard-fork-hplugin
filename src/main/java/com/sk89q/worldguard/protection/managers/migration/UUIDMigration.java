package com.sk89q.worldguard.protection.managers.migration;

import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.storage.RegionDatabase;
import com.sk89q.worldguard.protection.managers.storage.RegionDriver;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.enginehub.squirrelid.Profile;
import org.enginehub.squirrelid.resolver.ProfileService;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;

public class UUIDMigration extends AbstractMigration {
    private final ProfileService profiles;
    private final FlagRegistry flags;
    private final Map<String, UUID> resolved = new HashMap<>();
    private boolean keepUnresolvedNames = true;

    public UUIDMigration(RegionDriver driver, ProfileService profiles, FlagRegistry flags) {
        super(driver);
        this.profiles = profiles;
        this.flags = flags;
    }

    public boolean getKeepUnresolvedNames() {
        return keepUnresolvedNames;
    }

    public void setKeepUnresolvedNames(boolean value) {
        keepUnresolvedNames = value;
    }

    @Override
    protected void migrate(RegionDatabase store) throws MigrationException {
        try {
            Set<ProtectedRegion> regions = store.loadAll(flags);
            Set<String> names = new HashSet<>();
            for (ProtectedRegion region : regions) {
                names.addAll(region.getOwners().getPlayers());
                names.addAll(region.getMembers().getPlayers());
            }
            names.removeAll(resolved.keySet());
            if (!names.isEmpty()) {
                for (Profile profile : profiles.findAllByName(names)) {
                    resolved.put(profile.getName().toLowerCase(Locale.ROOT), profile.getUniqueId());
                }
            }
            for (ProtectedRegion region : regions) {
                convert(region.getOwners());
                convert(region.getMembers());
            }
            store.saveAll(regions);
        } catch (StorageException | IOException error) {
            throw new MigrationException("UUID migration failed for world '" + store.getName() + "'", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new MigrationException("UUID migration was interrupted", error);
        }
    }

    private void convert(DefaultDomain domain) {
        for (String name : domain.getPlayers()) {
            UUID uniqueId = resolved.get(name);
            if (uniqueId != null) {
                domain.addPlayer(uniqueId);
                domain.removePlayer(name);
            } else if (!keepUnresolvedNames) {
                domain.removePlayer(name);
            }
        }
    }

    @Override
    protected void postMigration() {
    }
}
