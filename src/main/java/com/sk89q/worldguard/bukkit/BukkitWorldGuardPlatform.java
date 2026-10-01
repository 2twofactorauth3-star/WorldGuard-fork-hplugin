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

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.gamemode.GameMode;
import com.sk89q.worldedit.world.gamemode.GameModes;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.enginehub.squirrelid.resolver.PaperPlayerService;
import com.sk89q.worldguard.bukkit.protection.events.flags.FlagContextCreateEvent;
import com.sk89q.worldguard.bukkit.session.BukkitSessionManager;
import com.sk89q.worldguard.internal.platform.StringMatcher;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.flags.FlagContext;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.session.SessionManager;
import org.enginehub.squirrelid.cache.ProfileCache;
import org.enginehub.squirrelid.resolver.CacheForwardingService;
import org.enginehub.squirrelid.resolver.CombinedProfileService;
import org.enginehub.squirrelid.resolver.HttpRepositoryService;
import org.enginehub.squirrelid.resolver.ProfileService;
import org.bukkit.Bukkit;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BukkitWorldGuardPlatform implements WorldGuardPlatform {

    private BukkitSessionManager sessionManager;
    private BukkitConfigurationManager configuration;
    private BukkitRegionContainer regionContainer;
    private StringMatcher stringMatcher;

    public BukkitWorldGuardPlatform() {
    }

    @Override
    public String getPlatformName() {
        return "Bukkit-Official";
    }

    @Override
    public String getPlatformVersion() {
        return WorldGuardPlugin.inst().getPluginMeta().getVersion();
    }

    @Override
    public void notifyFlagContextCreate(FlagContext.FlagContextBuilder flagContextBuilder) {
        Bukkit.getServer().getPluginManager().callEvent(new FlagContextCreateEvent(flagContextBuilder));
    }

    @Override
    public BukkitConfigurationManager getGlobalStateManager() {
        return configuration;
    }

    @Override
    public StringMatcher getMatcher() {
        return stringMatcher;
    }

    @Override
    public SessionManager getSessionManager() {
        return this.sessionManager;
    }

    @Override
    public void load() {
        stringMatcher = new BukkitStringMatcher();
        sessionManager = new BukkitSessionManager();
        configuration = new BukkitConfigurationManager(WorldGuardPlugin.inst());
        configuration.load();
        regionContainer = new BukkitRegionContainer(
                WorldGuardPlugin.inst(), WorldGuardPlugin.inst().isLocaleSelected());
        regionContainer.initialize();
    }

    @Override
    public void unload() {
        sessionManager.shutdown();
        configuration.unload();
        regionContainer.shutdown();
    }

    @Override
    public RegionContainer getRegionContainer() {
        return this.regionContainer;
    }

    @Override
    public GameMode getDefaultGameMode() {
        return GameModes.get(Bukkit.getServer().getDefaultGameMode().name().toLowerCase());
    }

    @Override
    public Path getConfigDir() {
        return WorldGuardPlugin.inst().getDataFolder().toPath();
    }

    @Override
    public ProfileService createProfileService(ProfileCache profileCache) {
        List<ProfileService> services = new ArrayList<>();
        services.add(PaperPlayerService.getInstance());
        services.add(HttpRepositoryService.forMinecraft());
        return new CacheForwardingService(new CombinedProfileService(services),
                profileCache);
    }

    @Nullable
    @Override
    public ProtectedRegion getSpawnProtection(World world) {
        if (world instanceof BukkitWorld) {
            org.bukkit.World bWorld = ((BukkitWorld) world).getWorld();
            if (bWorld.getUID().equals(Bukkit.getServer().getWorlds().getFirst().getUID())) {
                int radius = Bukkit.getServer().getSpawnRadius();
                if (radius > 0) {
                    BlockVector3 spawnLoc = BukkitAdapter.asBlockVector(bWorld.getSpawnLocation());
                    return new ProtectedCuboidRegion("__spawn_protection__",
                            spawnLoc.subtract(radius, 0, radius).withY(world.getMinimumPoint().y()),
                            spawnLoc.add(radius, 0, radius).withY(world.getMaxY()));
                }
            }
        }
        return null;
    }
}
