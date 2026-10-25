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

package com.sk89q.worldguard.config;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.blacklist.Blacklist;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.RegionAssociable;

import java.io.File;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Holds the configuration for individual worlds.
 *
 * @author sk89q
 * @author Michael
 */
public abstract class WorldConfiguration {

    public static final Logger log = Logger.getLogger(WorldConfiguration.class.getCanonicalName());

    public static final String CONFIG_HEADER = "#\r\n" +
            "# WorldGuard's world configuration file\r\n" +
            "#\r\n" +
            "# This is a world configuration file. Anything placed into here will only\r\n" +
            "# affect this world. If you don't put anything in this file, then the\r\n" +
            "# settings will be inherited from the main configuration file.\r\n" +
            "#\r\n" +
            "# If you see {} below, that means that there are NO entries in this file.\r\n" +
            "# Remove the {} and add your own entries.\r\n" +
            "#\r\n";

    public boolean boundedLocationFlags;
    protected File blacklistFile;
    protected Blacklist blacklist;
    public boolean useRegions;
    public boolean noPhysicsGravel;
    public WorldMechanicSetting noPhysicsGravelSetting = WorldMechanicSetting.DISABLED;
    public boolean noPhysicsSand;
    public WorldMechanicSetting noPhysicsSandSetting = WorldMechanicSetting.DISABLED;
    public boolean allowPortalAnywhere;
    public WorldMechanicSetting allowPortalAnywhereSetting = WorldMechanicSetting.DISABLED;
    public WorldMechanicSetting blockPistons;
    public WorldMechanicSetting preventWaterDamageSetting;
    public Set<String> preventWaterDamage;
    public boolean blockLighter;
    public WorldMechanicSetting blockLighterSetting = WorldMechanicSetting.DISABLED;
    public boolean disableFireSpread;
    public WorldMechanicSetting disableFireSpreadSetting = WorldMechanicSetting.DISABLED;
    public WorldMechanicSetting disableFireSpreadBlocksSetting;
    public Set<String> disableFireSpreadBlocks;
    public boolean preventLavaFire;
    public WorldMechanicSetting preventLavaFireSetting = WorldMechanicSetting.DISABLED;
    public WorldMechanicSetting allowedLavaSpreadOverSetting;
    public Set<String> allowedLavaSpreadOver;
    public boolean blockTNTExplosions;
    public WorldMechanicSetting blockTNTExplosionsSetting = WorldMechanicSetting.DISABLED;
    public boolean blockTNTBlockDamage;
    public WorldMechanicSetting blockTNTBlockDamageSetting = WorldMechanicSetting.DISABLED;
    public boolean blockCreeperExplosions;
    public WorldMechanicSetting blockCreeperExplosionsSetting = WorldMechanicSetting.DISABLED;
    public boolean blockCreeperBlockDamage;
    public WorldMechanicSetting blockCreeperBlockDamageSetting = WorldMechanicSetting.DISABLED;
    public boolean blockWitherExplosions;
    public WorldMechanicSetting blockWitherExplosionsSetting = WorldMechanicSetting.DISABLED;
    public boolean blockWitherBlockDamage;
    public WorldMechanicSetting blockWitherBlockDamageSetting = WorldMechanicSetting.DISABLED;
    public boolean blockWitherSkullExplosions;
    public WorldMechanicSetting blockWitherSkullExplosionsSetting = WorldMechanicSetting.DISABLED;
    public boolean blockWitherSkullBlockDamage;
    public WorldMechanicSetting blockWitherSkullBlockDamageSetting = WorldMechanicSetting.DISABLED;
    public boolean blockEnderDragonBlockDamage;
    public WorldMechanicSetting blockEnderDragonBlockDamageSetting = WorldMechanicSetting.DISABLED;
    public boolean blockEnderDragonPortalCreation;
    public WorldMechanicSetting blockEnderDragonPortalCreationSetting = WorldMechanicSetting.DISABLED;
    public boolean blockFireballExplosions;
    public WorldMechanicSetting blockFireballExplosionsSetting = WorldMechanicSetting.DISABLED;
    public boolean blockFireballBlockDamage;
    public WorldMechanicSetting blockFireballBlockDamageSetting = WorldMechanicSetting.DISABLED;
    public boolean blockWindChargeExplosions;
    public WorldMechanicSetting blockWindChargeExplosionsSetting = WorldMechanicSetting.DISABLED;
    public boolean blockOtherExplosions;
    public WorldMechanicSetting blockOtherExplosionsSetting = WorldMechanicSetting.DISABLED;
    public boolean blockEntityPaintingDestroy;
    public WorldMechanicSetting blockEntityPaintingDestroySetting = WorldMechanicSetting.DISABLED;
    public boolean blockEntityItemFrameDestroy;
    public WorldMechanicSetting blockEntityItemFrameDestroySetting = WorldMechanicSetting.DISABLED;
    public boolean blockEntityArmorStandDestroy;
    public WorldMechanicSetting blockEntityArmorStandDestroySetting = WorldMechanicSetting.DISABLED;
    public boolean blockEntityVehicleEntry;
    public WorldMechanicSetting blockEntityVehicleEntrySetting = WorldMechanicSetting.DISABLED;
    public boolean blockGroundSlimes;
    public WorldMechanicSetting blockGroundSlimesSetting = WorldMechanicSetting.DISABLED;
    public boolean blockZombieDoorDestruction;
    public WorldMechanicSetting blockZombieDoorDestructionSetting = WorldMechanicSetting.DISABLED;
    public boolean blockPluginSpawning;
    public boolean highFreqFlags;
    public boolean checkLiquidFlow;
    public boolean useMaxPriorityAssociation;
    public String regionWand;
    public int regionListCommandMode;
    public int maxClaimVolumePerPlayer;
    public ClaimExpansion claimExpansion;
    public ClaimExpansionOfferMode claimExpansionOfferMode;
    public boolean claimOnlyInsideExistingRegions;
    public boolean preventLastOwnerRemoval;
    public boolean allowMemberSelfRemoval;
    public boolean teleportCenterRequiresSpectator;
    public boolean applyRegionDefaultsToExistingRegions;
    public boolean showGlobalRegionInfo;
    public boolean showPlayerUuidsInRegionInfo;
    public boolean showRegionBlockCountInInfo;
    public boolean regionInfoNumberGroupingEnabled;
    public String regionInfoNumberGroupingSeparator;
    public boolean allowHopperMinecartAccess;
    public RegionDefaults newRegionDefaults;
    public RegionDefaults globalRegionDefaults;
    public int maxRegionCountPerPlayer;
    public boolean disableEndermanGriefing;
    public WorldMechanicSetting disableEndermanGriefingSetting = WorldMechanicSetting.DISABLED;
    public boolean disableSnowmanTrails;
    public WorldMechanicSetting disableSnowmanTrailsSetting = WorldMechanicSetting.DISABLED;
    public boolean regionInvinciblityRemovesMobs;
    public boolean regionNetherPortalProtection;
    public boolean forceDefaultTitleTimes;
    public boolean explosionFlagCancellation;
    protected Map<String, Integer> maxClaimVolumes;
    protected Map<String, Integer> maxRegionCounts;

    /**
     * Load the configuration.
     */
    public abstract void loadConfiguration();

    public Blacklist getBlacklist() {
        return blacklist;
    }

    public boolean blocksMechanic(boolean enabled, WorldMechanicSetting setting,
                                  Location target, RegionAssociable subject) {
        if (!enabled) return false;
        if (setting.mode == WorldMechanicSetting.Mode.EVERYWHERE) return true;
        ApplicableRegionSet regions = useRegions
                ? WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(target)
                : null;
        return setting.blocks(true, regions, subject);
    }

    public int getMaxRegionCount(LocalPlayer player) {
        return getHighestGroupValue(player, maxRegionCounts, maxRegionCountPerPlayer);
    }

    public int getMaxClaimVolume(LocalPlayer player) {
        return getHighestGroupValue(player, maxClaimVolumes, maxClaimVolumePerPlayer);
    }

    private static int getHighestGroupValue(LocalPlayer player, Map<String, Integer> values, int defaultValue) {
        int max = -1;
        for (String group : player.getGroups()) {
            Integer groupMax = values.get(group.toLowerCase(Locale.ROOT));
            if (groupMax != null && max < groupMax) {
                max = groupMax;
            }
        }
        if (max == -1) {
            max = defaultValue;
        }
        return max;
    }
}
