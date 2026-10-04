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
    public boolean useRegions;
    public WorldMechanicSetting noPhysicsGravel;
    public WorldMechanicSetting noPhysicsSand;
    public WorldMechanicSetting allowPortalAnywhere;
    public WorldMechanicSetting blockPistons;
    public WorldMechanicSetting preventWaterDamageSetting;
    public Set<String> preventWaterDamage;
    public WorldMechanicSetting blockLighter;
    public WorldMechanicSetting disableFireSpread;
    public WorldMechanicSetting disableFireSpreadBlocksSetting;
    public Set<String> disableFireSpreadBlocks;
    public WorldMechanicSetting preventLavaFire;
    public WorldMechanicSetting allowedLavaSpreadOverSetting;
    public Set<String> allowedLavaSpreadOver;
    public WorldMechanicSetting blockTNTExplosions;
    public WorldMechanicSetting blockTNTBlockDamage;
    public WorldMechanicSetting blockCreeperExplosions;
    public WorldMechanicSetting blockCreeperBlockDamage;
    public WorldMechanicSetting blockWitherExplosions;
    public WorldMechanicSetting blockWitherBlockDamage;
    public WorldMechanicSetting blockWitherSkullExplosions;
    public WorldMechanicSetting blockWitherSkullBlockDamage;
    public WorldMechanicSetting blockEnderDragonBlockDamage;
    public WorldMechanicSetting blockEnderDragonPortalCreation;
    public WorldMechanicSetting blockFireballExplosions;
    public WorldMechanicSetting blockFireballBlockDamage;
    public WorldMechanicSetting blockWindChargeExplosions;
    public WorldMechanicSetting blockOtherExplosions;
    public WorldMechanicSetting blockEntityPaintingDestroy;
    public WorldMechanicSetting blockEntityItemFrameDestroy;
    public WorldMechanicSetting blockEntityArmorStandDestroy;
    public WorldMechanicSetting blockEntityVehicleEntry;
    public WorldMechanicSetting blockGroundSlimes;
    public WorldMechanicSetting blockZombieDoorDestruction;
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
    public WorldMechanicSetting disableEndermanGriefing;
    public WorldMechanicSetting disableSnowmanTrails;
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
