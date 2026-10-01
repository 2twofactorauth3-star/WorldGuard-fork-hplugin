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

import com.sk89q.util.yaml.YAMLFormat;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldguard.protection.managers.storage.file.DirectoryYamlDriver;

import java.io.File;
import java.io.IOException;

public abstract class YamlConfigurationManager extends ConfigurationManager {

    private YAMLProcessor config;

    public abstract void copyDefaults();

    @Override
    public void load() {
        copyDefaults();

        config = createYamlProcessor(new File(getDataFolder(), "config.yml"));
        try {
            config.load();
        } catch (IOException e) {
            log.log(java.util.logging.Level.SEVERE,
                    "@wglog:logErrorReadingConfigurationForGlobalConfig@", e);
        }

        useRegionsCreatureSpawnEvent = getBoolean("regions.useCreatureSpawnEvent", true);
        disableDefaultBypass = getBoolean("regions.disableBypassByDefault", false);
        announceBypassStatus = getBoolean("regions.announceBypassStatus", false);
        showBlockedCommandMessagesInRegionInfo = getBoolean(
                "regions.showBlockedCommandMessagesInRegionInfo", false);

        particleEffects = getBoolean("useParticleEffects", true);
        showSelectionBorders = getBoolean("selectionParticles.enable", true);
        selectionParticleSize = (float) clamp(getNumber("selectionParticles.size", 2.5), 0.1, 4.0);
        selectionParticleSpacing = clamp(getNumber("selectionParticles.spacing", 0.5), 0.1, 16.0);
        selectionParticleMaxCount = (int) clamp(getNumber("selectionParticles.maxCount", 512), 12, 5000);
        selectionParticleViewDistance = clamp(getNumber("selectionParticles.viewDistance", 128), 1, 512);
        selectionParticleUpdatePeriodTicks = (long) clamp(
                getNumber("selectionParticles.updatePeriodTicks", 10), 1, 1200);
        double clearDelaySeconds = getNumber("selectionParticles.clearAfterSuccessfulActionSeconds", 30);
        selectionClearAfterSuccessfulActionSeconds = clearDelaySeconds < 0
                ? -1 : (int) clamp(clearDelaySeconds, 0, 86400);
        double maximumLifetimeSeconds = getNumber("selectionParticles.maximumLifetimeSeconds", 180);
        selectionMaximumLifetimeSeconds = maximumLifetimeSeconds < 0
                ? -1 : (int) clamp(maximumLifetimeSeconds, 1, 86400);
        selectionParticleRed = (int) clamp(getNumber("selectionParticles.color.red", 253), 0, 255);
        selectionParticleGreen = (int) clamp(getNumber("selectionParticles.color.green", 190), 0, 255);
        selectionParticleBlue = (int) clamp(getNumber("selectionParticles.color.blue", 0), 0, 255);
        confirmOfflinePlayerAdditions = getBoolean("confirmOfflinePlayerAdditions", true);

        this.selectedRegionStoreDriver = new DirectoryYamlDriver(getWorldsDataFolder(), "regions.yml");

        postLoad();

        config.setHeader(CONFIG_HEADER);
    }

    public void postLoad() {}

    protected YAMLProcessor createYamlProcessor(File file) {
        return new YAMLProcessor(file, true, YAMLFormat.EXTENDED);
    }

    public YAMLProcessor getConfig() {
        return config;
    }

    protected final String settingsPath(String node) {
        return node.startsWith("settings.") ? node : "settings." + node;
    }

    protected final boolean getBoolean(String node, boolean def) {
        return config.getBoolean(settingsPath(node), def);
    }

    private double getNumber(String node, double def) {
        Object value = config.getProperty(settingsPath(node));
        return value instanceof Number number ? number.doubleValue() : def;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

}
