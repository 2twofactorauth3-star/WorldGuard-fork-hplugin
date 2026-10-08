/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.sk89q.worldguard.bukkit;

import com.sk89q.util.yaml.YAMLFormat;
import com.sk89q.util.yaml.YAMLNode;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldguard.config.RegionDefaults;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/** Loads defaults applied to new regions and the main global region. */
final class BukkitRegionDefaults {

    private final WorldGuardPlugin plugin;
    private final File file;

    private volatile Map<String, WorldDefaults> worlds = Map.of();

    BukkitRegionDefaults(WorldGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "regionDefaults.yml");
    }

    void load() {
        plugin.createDefaultConfiguration(file, "regionDefaults.yml");
        YAMLProcessor config = new YAMLProcessor(file, false, YAMLFormat.EXTENDED);
        try {
            BukkitConfigValidator.validateNoDuplicateKeys(plugin, file);
            config.load();
            if (!config.getBoolean("settings.enable", false)) {
                worlds = Map.of();
                return;
            }
            BukkitConfigValidator.validateRegionDefaults(plugin, config, file);
            Map<String, WorldDefaults> loaded = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry
                    : readMap(config.getProperty("settings.worlds")).entrySet()) {
                Map<String, Object> world = readMap(entry.getValue());
                loaded.put(entry.getKey(), new WorldDefaults(
                        readOptionalBoolean(world, "applyToExistingRegions"),
                        readOptionalBoolean(world, "showGlobalRegionInfo"),
                        world.containsKey("newRegions") ? read(world.get("newRegions"), true) : null,
                        world.containsKey("globalRegion") ? read(world.get("globalRegion"), false) : null));
            }
            worlds = Map.copyOf(loaded);
        } catch (IOException | YAMLException e) {
            worlds = Map.of();
            plugin.getLogger().log(Level.SEVERE, "@wglog:unableLoadRegionDefaults@", e);
        }
    }

    RegionDefaults newRegions(String worldId) {
        return defaults(worldId).newRegions;
    }

    RegionDefaults globalRegion(String worldId) {
        return defaults(worldId).globalRegion;
    }

    boolean applyToExistingRegions(String worldId) {
        return defaults(worldId).applyToExistingRegions;
    }

    boolean showGlobalRegionInfo(String worldId) {
        return defaults(worldId).showGlobalRegionInfo;
    }

    private WorldDefaults defaults(String worldId) {
        WorldDefaults wildcard = worlds.getOrDefault("*", WorldDefaults.EMPTY)
                .withFallback(WorldDefaults.EMPTY);
        WorldDefaults exact = worlds.get(worldId);
        if (exact == null || exact == worlds.get("*")) {
            return wildcard;
        }
        return exact.withFallback(wildcard);
    }

    static RegionDefaults read(YAMLProcessor config, String path, boolean includeDomains) {
        return read(config.getProperty(path), includeDomains);
    }

    private static RegionDefaults read(Object value, boolean includeDomains) {
        Map<String, Object> section = readMap(value);
        Map<String, Object> flags = readMap(section.get("flags"));
        if (includeDomains) {
            addBlockedCommandDefaults(section.get("blockedCommands"), flags);
        }
        return new RegionDefaults(
                readInt(section.get("priority"), 0),
                readString(section.get("parent"), ""),
                flags,
                readLines(section.get("clearFlags")),
                includeDomains ? readLines(readMap(section.get("owners")).get("players")) : List.of(),
                includeDomains ? readLines(readMap(section.get("owners")).get("groups")) : List.of(),
                includeDomains ? readLines(readMap(section.get("members")).get("players")) : List.of(),
                includeDomains ? readLines(readMap(section.get("members")).get("groups")) : List.of());
    }

    private static void addBlockedCommandDefaults(Object value, Map<String, Object> flags) {
        Map<String, Object> categories = readMap(value);
        Set<String> commands = new LinkedHashSet<>();
        Map<String, String> messages = new LinkedHashMap<>();
        for (Object categoryValue : categories.values()) {
            Map<String, Object> category = readMap(categoryValue);
            String message = String.join("\n", BukkitMessages.readLines(category.get("message")));
            for (String configured : BukkitMessages.readLines(category.get("commands"))) {
                String command = configured.trim().toLowerCase(Locale.ROOT);
                if (command.isEmpty()) {
                    continue;
                }
                command = command.startsWith("/") ? command : "/" + command;
                if (commands.add(command)) {
                    messages.put(command, message);
                }
            }
        }
        if (commands.isEmpty()) {
            return;
        }

        flags.put("blocked-cmds", List.copyOf(commands));
        flags.put("blocked-cmds-group", RegionGroup.NON_MEMBERS);
        flags.put("blocked-cmds-messages", messages);
    }

    private static int readInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean readBoolean(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static Boolean readOptionalBoolean(Map<String, Object> section, String key) {
        return section.containsKey(key) ? readBoolean(section.get(key), false) : null;
    }

    private static String readString(Object value, String fallback) {
        return value instanceof String string ? string : fallback;
    }

    private static List<String> readLines(Object value) {
        return List.copyOf(BukkitMessages.readLines(value));
    }

    private static Map<String, Object> readMap(Object value) {
        Map<?, ?> source;
        if (value instanceof YAMLNode node) {
            source = node.getMap();
        } else if (value instanceof Map<?, ?> map) {
            source = map;
        } else {
            return new LinkedHashMap<>();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() != null) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static RegionDefaults emptyDefaults() {
        return new RegionDefaults(0, "", Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static final class WorldDefaults {
        private static final WorldDefaults EMPTY =
                new WorldDefaults(false, false, emptyDefaults(), emptyDefaults());

        public final Boolean applyToExistingRegions;
        public final Boolean showGlobalRegionInfo;
        public final RegionDefaults newRegions;
        public final RegionDefaults globalRegion;

        private WorldDefaults(
                Boolean applyToExistingRegions,
                Boolean showGlobalRegionInfo,
                RegionDefaults newRegions,
                RegionDefaults globalRegion) {
            this.applyToExistingRegions = applyToExistingRegions;
            this.showGlobalRegionInfo = showGlobalRegionInfo;
            this.newRegions = newRegions;
            this.globalRegion = globalRegion;
        }

        private WorldDefaults withFallback(WorldDefaults fallback) {
            return new WorldDefaults(
                    applyToExistingRegions != null
                            ? applyToExistingRegions : fallback.applyToExistingRegions,
                    showGlobalRegionInfo != null
                            ? showGlobalRegionInfo : fallback.showGlobalRegionInfo,
                    newRegions != null ? newRegions : fallback.newRegions,
                    globalRegion != null ? globalRegion : fallback.globalRegion);
        }
    }
}
