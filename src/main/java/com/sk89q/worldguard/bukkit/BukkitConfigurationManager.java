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
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.config.YamlConfigurationManager;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BukkitConfigurationManager extends YamlConfigurationManager {

    private final WorldGuardPlugin plugin;
    private final ConcurrentMap<String, BukkitWorldConfiguration> worlds = new ConcurrentHashMap<>();

    /**
     * Construct the object.
     *
     * @param plugin The plugin instance
     */
    public BukkitConfigurationManager(WorldGuardPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    @Override
    public void load() {
        plugin.getLocaleManager().invalidateConfigValues();
        plugin.getLogs().load();
        BukkitConfigValidator.validateNoDuplicateKeys(
                plugin, new File(plugin.getDataFolder(), "config.yml"));
        BukkitConfigValidator.validatePrefix(plugin);
        plugin.getMessages().load();
        plugin.getRegionDefaults().load();
        super.load();
    }

    @Override
    public File getDataFolder() {
        return plugin.getDataFolder();
    }

    @Override
    public void copyDefaults() {
        // Create the default configuration file
        plugin.createDefaultConfiguration(new File(plugin.getDataFolder(), "config.yml"), "config.yml");
    }

    @Override
    protected YAMLProcessor createYamlProcessor(File file) {
        return new CommentedYamlProcessor(
                file,
                () -> plugin.getDefaultConfigurationResource("config.yml"),
                plugin.getLogger());
    }

    @Override
    public void unload() {
        worlds.clear();
    }

    @Override
    public void postLoad() {
        if (!plugin.isLocaleSelected()) {
            getConfig().save();
            return;
        }
        // Load configurations for each world
        for (World world : WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.GAME_HOOKS).getWorlds()) {
            get(world);
        }
        getConfig().save();
    }

    /**
     * Get the configuration for a world.
     *
     * @param world The world to get the configuration for
     * @return {@code world}'s configuration
     */
    @Override
    public BukkitWorldConfiguration get(World world) {
        String worldName = world.getName();
        return get(worldName);
    }

    public BukkitWorldConfiguration get(String worldName) {
        return worlds.computeIfAbsent(worldName,
                name -> new BukkitWorldConfiguration(plugin, name, this.getConfig()));
    }

}
