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

import com.sk89q.util.yaml.YAMLNode;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Performs startup-only validation of administrator-editable configuration. */
final class BukkitConfigValidator {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final java.util.regex.Pattern MINI_MESSAGE_TAG =
            java.util.regex.Pattern.compile("</?([A-Za-z_][A-Za-z0-9_-]*)(?=[:>])");
    private static final Set<String> MINI_MESSAGE_TAGS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white", "color", "colour",
            "bold", "b", "italic", "i", "underlined", "u", "strikethrough", "st",
            "obfuscated", "obf", "reset", "shadow_color", "gradient", "rainbow",
            "transition", "font", "click", "hover", "insertion", "key", "keybind",
            "translatable", "lang", "selector", "score", "nbt", "newline", "br", "pride");

    private BukkitConfigValidator() {
    }

    static void validateNoDuplicateKeys(WorldGuardPlugin plugin, File file) {
        if (!file.isFile()) {
            return;
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            new Yaml(new SafeConstructor(options)).load(reader);
        } catch (IOException | YAMLException e) {
            warn(plugin, "@wglog:configDuplicateYamlKey@" + file.getName()
                    + "@wglog:configValidationDetails@" + conciseMessage(e));
        }
    }

    static void validatePrefix(WorldGuardPlugin plugin) {
        validateMiniMessage(plugin, plugin.getLocaleManager().configuredPrefix(),
                "settings.prefix", "config.yml");
    }

    static void validateMessages(
            WorldGuardPlugin plugin, YAMLProcessor config, File file, int actionBarMultipleLinesMode) {
        for (Map.Entry<String, List<String>> entry : BukkitMessages.readMessages(config).entrySet()) {
            for (String line : entry.getValue()) {
                validateMiniMessage(plugin, line, "messages." + entry.getKey(), file.getName());
            }
        }
        validateMessageDefinitions(plugin, config.getProperty("messages"), "messages", file.getName(),
                actionBarMultipleLinesMode);
    }

    static void validateRegionDefaults(WorldGuardPlugin plugin, YAMLProcessor config, File file) {
        Collection<String> worldIds = config.getKeys("settings.worlds");
        if (worldIds == null) {
            return;
        }
        for (String worldId : worldIds) {
            String base = "settings.worlds." + worldId;
            validateFlags(plugin, config, base + ".newRegions.flags", file.getName());
            validateFlags(plugin, config, base + ".globalRegion.flags", file.getName());
            validateClearFlags(plugin, config, base + ".globalRegion.clearFlags", file.getName());
            validateUuidList(plugin, config, base + ".newRegions.owners.players", file.getName());
            validateUuidList(plugin, config, base + ".newRegions.members.players", file.getName());
            validateBlockedCommands(plugin, config, base + ".newRegions.blockedCommands", file.getName());
        }
    }

    static void validateLimitGroups(WorldGuardPlugin plugin, String worldName,
                                    Set<String> regionCountGroups, Set<String> claimVolumeGroups) {
        Set<String> counts = normalizedGroups(regionCountGroups);
        Set<String> volumes = normalizedGroups(claimVolumeGroups);
        Set<String> mismatched = new LinkedHashSet<>(counts);
        mismatched.addAll(volumes);
        Set<String> common = new LinkedHashSet<>(counts);
        common.retainAll(volumes);
        mismatched.removeAll(common);
        for (String group : mismatched) {
            warn(plugin, "@wglog:configUnknownLimitGroup@" + group
                    + "@wglog:configForWorld@" + worldName);
        }
    }

    private static void validateMessageDefinitions(
            WorldGuardPlugin plugin, Object value, String path, String fileName,
            int actionBarMultipleLinesMode) {
        Map<String, Object> map = readMap(value);
        if (map.isEmpty()) {
            return;
        }
        if (map.containsKey("message") || map.containsKey("mode") || map.containsKey("cooldownMillis")) {
            Object mode = map.get("mode");
            if (!(mode instanceof String string)
                    || (!string.equalsIgnoreCase("message")
                    && !string.equalsIgnoreCase("actionbar")
                    && !string.equalsIgnoreCase("title"))) {
                warn(plugin, "@wglog:configInvalidMessageMode@" + path
                        + "@wglog:configInFile@" + fileName);
            }
            Object cooldown = map.get("cooldownMillis");
            if (!(cooldown instanceof Number number) || number.longValue() < -1) {
                warn(plugin, "@wglog:configInvalidCooldown@" + path
                        + "@wglog:configInFile@" + fileName);
            }
            if (mode instanceof String string && string.equalsIgnoreCase("actionbar")
                    && actionBarMultipleLinesMode == 1
                    && BukkitMessages.readLines(map.get("message")).size() > 1) {
                warn(plugin, "@wglog:configActionbarMultipleLinesRejected@" + path
                        + "@wglog:configInFile@" + fileName);
            }
            if (mode instanceof String string && string.equalsIgnoreCase("title")
                    && map.get("message") instanceof java.util.Collection<?> lines && lines.size() > 2) {
                warn(plugin, "@wglog:configTitleRequiresTwoLines@" + path
                        + "@wglog:configInFile@" + fileName);
            }
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            validateMessageDefinitions(plugin, entry.getValue(), path + "." + entry.getKey(), fileName,
                    actionBarMultipleLinesMode);
        }
    }

    private static void validateFlags(
            WorldGuardPlugin plugin, YAMLProcessor config, String path, String fileName) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        for (Map.Entry<String, Object> entry : readMap(config.getProperty(path)).entrySet()) {
            String configuredName = entry.getKey();
            String flagName = configuredName.endsWith("-group")
                    ? configuredName.substring(0, configuredName.length() - "-group".length())
                    : configuredName;
            Flag<?> flag = registry.get(flagName);
            if (flag == null || configuredName.endsWith("-group") && flag.getRegionGroupFlag() == null) {
                warn(plugin, "@wglog:configUnknownFlag@" + configuredName
                        + "@wglog:configInFile@" + fileName);
                continue;
            }
            try {
                registry.unmarshal(Map.of(configuredName, entry.getValue()), false);
            } catch (RuntimeException e) {
                warn(plugin, "@wglog:configInvalidFlagValue@" + configuredName
                        + "@wglog:configInFile@" + fileName
                        + "@wglog:configValidationDetails@" + conciseMessage(e));
            }
        }
    }

    private static void validateClearFlags(
            WorldGuardPlugin plugin, YAMLProcessor config, String path, String fileName) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        for (String configuredName : config.getStringList(path, List.of())) {
            String flagName = configuredName.endsWith("-group")
                    ? configuredName.substring(0, configuredName.length() - "-group".length())
                    : configuredName;
            Flag<?> flag = registry.get(flagName);
            if (flag == null || configuredName.endsWith("-group") && flag.getRegionGroupFlag() == null) {
                warn(plugin, "@wglog:configUnknownFlag@" + configuredName
                        + "@wglog:configInFile@" + fileName);
            }
        }
    }

    private static void validateUuidList(
            WorldGuardPlugin plugin, YAMLProcessor config, String path, String fileName) {
        for (String value : config.getStringList(path, List.of())) {
            try {
                UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                warn(plugin, "@wglog:configInvalidUuid@" + value
                        + "@wglog:configAtPath@" + path
                        + "@wglog:configInFile@" + fileName);
            }
        }
    }

    private static void validateBlockedCommands(
            WorldGuardPlugin plugin, YAMLProcessor config, String path, String fileName) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (Map.Entry<String, Object> categoryEntry : readMap(config.getProperty(path)).entrySet()) {
            String category = categoryEntry.getKey();
            Map<String, Object> definition = readMap(categoryEntry.getValue());
            for (String configured : BukkitMessages.readLines(definition.get("commands"))) {
                String command = configured.strip().toLowerCase(Locale.ROOT);
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                command = command.replaceAll("\\s+", " ");
                if (command.isEmpty()) {
                    continue;
                }
                String previous = owners.putIfAbsent(command, category);
                if (previous != null) {
                    warn(plugin, "@wglog:configDuplicateBlockedCommand@/" + command
                            + "@wglog:configValidationDetails@" + previous + ", " + category);
                }
            }
            for (String message : BukkitMessages.readLines(definition.get("message"))) {
                validateMiniMessage(plugin, message, path + "." + category + ".message", fileName);
            }
        }
    }

    private static void validateMiniMessage(
            WorldGuardPlugin plugin, String value, String path, String fileName) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            MINI_MESSAGE.deserialize(value);
        } catch (RuntimeException e) {
            warn(plugin, "@wglog:configInvalidMiniMessage@" + path
                    + "@wglog:configInFile@" + fileName
                    + "@wglog:configValidationDetails@" + conciseMessage(e));
            return;
        }
        java.util.regex.Matcher matcher = MINI_MESSAGE_TAG.matcher(value);
        while (matcher.find()) {
            String tag = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!MINI_MESSAGE_TAGS.contains(tag)) {
                warn(plugin, "@wglog:configInvalidMiniMessage@" + path
                        + "@wglog:configInFile@" + fileName
                        + "@wglog:configValidationDetails@"
                        + "@wglog:configUnknownMiniMessageTag@<" + tag + ">");
                return;
            }
        }
    }

    private static Set<String> normalizedGroups(Set<String> input) {
        Set<String> output = new LinkedHashSet<>();
        for (String group : input) {
            if (group != null && !group.equalsIgnoreCase("default")) {
                output.add(group.toLowerCase(Locale.ROOT));
            }
        }
        return output;
    }

    private static Map<String, Object> readMap(Object value) {
        Map<?, ?> source;
        if (value instanceof YAMLNode node) {
            source = node.getMap();
        } else if (value instanceof Map<?, ?> map) {
            source = map;
        } else {
            return Map.of();
        }
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                output.put(key, entry.getValue());
            }
        }
        return output;
    }

    private static String conciseMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    private static void warn(WorldGuardPlugin plugin, String message) {
        plugin.getLogger().warning(message);
    }
}
