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

import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldguard.config.ClaimExpansion;
import com.sk89q.worldguard.config.WorldMechanicSetting;
import com.sk89q.worldguard.config.YamlWorldConfiguration;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Holds the configuration for individual worlds.
 *
 * @author sk89q
 * @author Michael
 */
public class BukkitWorldConfiguration extends YamlWorldConfiguration {

    private final WorldGuardPlugin plugin;
    private final String worldName;
    private final File configFile;

    /* Configuration data start */
    /* Configuration data end */

    /**
     * Construct the object.
     *
     * @param plugin The WorldGuardPlugin instance
     * @param worldName The world name that this BukkitWorldConfiguration is for.
     * @param parentConfig The parent configuration to read defaults from
     */
    @SuppressWarnings("this-escape")
    public BukkitWorldConfiguration(WorldGuardPlugin plugin, String worldName, YAMLProcessor parentConfig) {
        this.plugin = plugin;
        File baseFolder = new File(plugin.getDataFolder(), "worlds/" + worldName);
        configFile = new File(baseFolder, "config.yml");

        this.worldName = worldName;
        this.parentConfig = parentConfig;

        plugin.createDefaultConfiguration(configFile, "configWorld.yml");

        config = new CommentedYamlProcessor(
                configFile,
                () -> plugin.getDefaultConfigurationResource("configWorld.yml"),
                plugin.getLogger());
        loadConfiguration();
    }

    /**
     * Load the configuration.
     */
    @Override
    public void loadConfiguration() {
        try {
            BukkitConfigValidator.validateNoDuplicateKeys(plugin, configFile);
            config.load();
        } catch (IOException e) {
            log.log(Level.SEVERE, "@wglog:logErrorReadingConfigurationForWorld@" + worldName + "@wglog:logText9@", e);
        } catch (YAMLException e) {
            log.severe("@wglog:logErrorParsingConfigurationForWorld@" + worldName + "@wglog:logText10@");
            throw e;
        }

        noPhysicsGravel = mechanic("physics.noPhysicsGravel");
        noPhysicsSand = mechanic("physics.noPhysicsSand");
        allowPortalAnywhere = mechanic("physics.allowPortalAnywhere");
        preventWaterDamageSetting = mechanic("physics.disableWaterDamageBlocks");
        preventWaterDamage = Set.copyOf(
                getStringList("physics.disableWaterDamageBlocks.blocks", List.of()));

        blockTNTExplosions = mechanic("ignition.blockTnt");
        blockTNTBlockDamage = mechanic("ignition.blockTntBlockDamage");
        blockLighter = mechanic("ignition.blockLighter");

        preventLavaFire = mechanic("fire.disableLavaFireSpread");
        disableFireSpread = mechanic("fire.disableAllFireSpread");
        disableFireSpreadBlocksSetting = mechanic("fire.disableFireSpreadBlocks");
        disableFireSpreadBlocks = Set.copyOf(
                getStringList("fire.disableFireSpreadBlocks.blocks", List.of()));
        allowedLavaSpreadOverSetting = mechanic("fire.lavaSpreadBlocks");
        allowedLavaSpreadOver = Set.copyOf(
                getStringList("fire.lavaSpreadBlocks.blocks", List.of()));

        blockCreeperExplosions = mechanic("mobs.blockCreeperExplosions");
        blockCreeperBlockDamage = mechanic("mobs.blockCreeperBlockDamage");
        blockWitherExplosions = mechanic("mobs.blockWitherExplosions");
        blockWitherBlockDamage = mechanic("mobs.blockWitherBlockDamage");
        blockWitherSkullExplosions = mechanic("mobs.blockWitherSkullExplosions");
        blockWitherSkullBlockDamage = mechanic("mobs.blockWitherSkullBlockDamage");
        blockEnderDragonBlockDamage = mechanic("mobs.blockEnderdragonBlockDamage");
        blockEnderDragonPortalCreation = mechanic("mobs.blockEnderdragonPortalCreation");
        blockFireballExplosions = mechanic("mobs.blockFireballExplosions");
        blockFireballBlockDamage = mechanic("mobs.blockFireballBlockDamage");
        blockWindChargeExplosions = mechanic("mobs.blockWindchargeExplosions");
        disableEndermanGriefing = mechanic("mobs.disableEndermanGriefing");
        disableSnowmanTrails = mechanic("mobs.disableSnowmanTrails");
        blockEntityPaintingDestroy = mechanic("mobs.blockPaintingDestroy");
        blockEntityItemFrameDestroy = mechanic("mobs.blockItemFrameDestroy");
        blockEntityArmorStandDestroy = mechanic("mobs.blockArmorStandDestroy");
        blockGroundSlimes = mechanic("mobs.blockAboveGroundSlimes");
        blockOtherExplosions = mechanic("mobs.blockOtherExplosions");
        blockZombieDoorDestruction = mechanic("mobs.blockZombieDoorDestruction");
        blockEntityVehicleEntry = mechanic("mobs.blockVehicleEntry");
        blockPluginSpawning = getBoolean("mobs.blockPluginSpawning",
                getBoolean("event-handling.block-plugin-spawning", true));

        useRegions = getBoolean("regions.enable", true);
        regionInvinciblityRemovesMobs = getBoolean("regions.invincibilityRemovesMobs", false);
        regionNetherPortalProtection = getBoolean("regions.netherPortalProtection", true);
        forceDefaultTitleTimes = getBoolean("regions.titlesAlwaysUseDefaultTimes", true); // note: technically not region-specific, but we only use it for the title flags
        explosionFlagCancellation = getBoolean("regions.explosionFlagsBlockEntityDamage", true);
        highFreqFlags = getBoolean("regions.highFrequencyFlags", false);
        checkLiquidFlow = getBoolean("regions.protectAgainstLiquidFlow", false);
        useMaxPriorityAssociation = getBoolean("regions.useMaxPriorityAssociation",
                getBoolean("regions.use-max-priority-association", false));
        regionListCommandMode = getInt("regions.listCommandMode", 2);
        if (regionListCommandMode != 1 && regionListCommandMode != 2) {
            regionListCommandMode = 2;
        }
        regionWand = getString("regions.wand", "minecraft:leather");
        maxClaimVolumePerPlayer = getInt("regions.maxClaimVolumePerPlayer.default", 1500000);
        maxClaimVolumes = loadGroupLimits("regions.maxClaimVolumePerPlayer", maxClaimVolumePerPlayer);
        claimExpansion = new ClaimExpansion(
                claimExpansionEnabled("regions.claimExpansion.negativeY", true),
                claimExpansionDistance("regions.claimExpansion.negativeY"),
                claimExpansionEnabled("regions.claimExpansion.positiveY", true),
                claimExpansionDistance("regions.claimExpansion.positiveY"),
                claimExpansionEnabled("regions.claimExpansion.negativeX", false),
                claimExpansionDistance("regions.claimExpansion.negativeX"),
                claimExpansionEnabled("regions.claimExpansion.positiveX", false),
                claimExpansionDistance("regions.claimExpansion.positiveX"),
                claimExpansionEnabled("regions.claimExpansion.negativeZ", false),
                claimExpansionDistance("regions.claimExpansion.negativeZ"),
                claimExpansionEnabled("regions.claimExpansion.positiveZ", false),
                claimExpansionDistance("regions.claimExpansion.positiveZ"));
        claimOnlyInsideExistingRegions = getBoolean("regions.claimOnlyInsideExistingRegions", false);
        preventLastOwnerRemoval = getBoolean("regions.preventLastOwnerRemoval", true);
        applyRegionDefaultsToExistingRegions =
                plugin.getRegionDefaults().applyToExistingRegions(worldName);
        showGlobalRegionInfo = plugin.getRegionDefaults().showGlobalRegionInfo(worldName);
        showPlayerUuidsInRegionInfo = getBoolean("regions.showPlayerUuidsInRegionInfo", false);
        allowHopperMinecartAccess = getBoolean("regions.allowHopperMinecartAccess", true);
        newRegionDefaults = plugin.getRegionDefaults().newRegions(worldName);
        globalRegionDefaults = plugin.getRegionDefaults().globalRegion(worldName);
        boundedLocationFlags = getBoolean("regions.locationFlagsOnlyInsideRegions", false);

        maxRegionCountPerPlayer = getInt("regions.maxRegionCountPerPlayer.default", 1);
        maxRegionCounts = loadGroupLimits("regions.maxRegionCountPerPlayer", maxRegionCountPerPlayer);
        BukkitConfigValidator.validateLimitGroups(plugin, worldName,
                maxRegionCounts.keySet(), maxClaimVolumes.keySet());

        config.setHeader(CONFIG_HEADER);

        config.save();
    }

    private WorldMechanicSetting mechanic(String path) {
        int mode = getInt(path + ".mode", WorldMechanicSetting.Mode.EVERYWHERE.configValue);
        if (!WorldMechanicSetting.Mode.isValidConfigValue(mode)) {
            plugin.getLogger().warning("@wglog:configInvalidMechanicMode@" + mode
                    + "@wglog:configAtPath@" + path + "@wglog:configForWorld@" + worldName);
            mode = WorldMechanicSetting.Mode.EVERYWHERE.configValue;
        }
        return new WorldMechanicSetting(
                getBoolean(path + ".enable", false),
                mode);
    }

    private boolean claimExpansionEnabled(String path, boolean defaultValue) {
        Object value = getProperty(path);
        return value instanceof Boolean enabled
                ? enabled : getBoolean(path + ".enable", defaultValue);
    }

    private int claimExpansionDistance(String path) {
        return Math.max(-1, getInt(path + ".maxDistance", -1));
    }

    private HashMap<String, Integer> loadGroupLimits(String path, int defaultValue) {
        HashMap<String, Integer> limits = new HashMap<>();
        limits.put(null, defaultValue);
        for (String key : getKeys(path)) {
            if (!key.equalsIgnoreCase("default")) {
                Object value = getProperty(path + "." + key);
                if (value instanceof Number number) {
                    limits.put(key.toLowerCase(Locale.ROOT), number.intValue());
                }
            }
        }
        return limits;
    }

}
