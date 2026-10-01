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
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldguard.util.logging.LogMessages;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Loads and applies messages written to the server log. */
public final class BukkitLogs {

    private final WorldGuardPlugin plugin;
    private final File file;
    private volatile Map<String, List<String>> messages = Map.of();
    private volatile String prefix = "";

    public BukkitLogs(WorldGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "logs.yml");
    }

    public void load() {
        plugin.createDefaultConfiguration(file, "logs.yml");
        prefix = plugin.getLocaleManager().configuredPrefix();
        YAMLProcessor config = new YAMLProcessor(file, false, YAMLFormat.EXTENDED);
        try {
            BukkitConfigValidator.validateNoDuplicateKeys(plugin, file);
            config.load();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "@wglog:unableLoadLogs@", e);
            messages = Map.of();
            return;
        }

        Map<String, List<String>> loaded = new LinkedHashMap<>();
        Collection<String> keys = config.getKeys("logs");
        if (keys != null) {
            for (String key : keys) {
                loaded.put(key, BukkitMessages.readLines(config.getProperty("logs." + key + ".message")));
            }
        }
        messages = Map.copyOf(loaded);
        LogMessages.setResolver(this::resolve);
    }

    public String resolve(String input) {
        return BukkitMessages.replacePlaceholder(resolve(input, messages), "prefix", prefix);
    }

    public static void installBundledDefaults(WorldGuardPlugin plugin, String locale) {
        Map<String, List<String>> bundled = readBundled(plugin, locale);
        LogMessages.setResolver(input -> resolve(input, bundled));
    }

    private static Map<String, List<String>> readBundled(WorldGuardPlugin plugin, String locale) {
        try (InputStream input = plugin.getResource("locale/" + locale + "/logs.yml")) {
            if (input == null) return Map.of();
            Object rootValue = new Yaml().load(input);
            if (!(rootValue instanceof Map<?, ?> root)) return Map.of();
            Object logsValue = root.get("logs");
            if (!(logsValue instanceof Map<?, ?> logs)) return Map.of();
            Map<String, List<String>> loaded = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : logs.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Map<?, ?> value)) continue;
                loaded.put(key, BukkitMessages.readLines(value.get("message")));
            }
            return Map.copyOf(loaded);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private static String resolve(String input, Map<String, List<String>> messages) {
        if (input == null || input.isEmpty()) return "";
        String resolved = input;
        int searchFrom = 0;
        while (true) {
            int start = resolved.indexOf("@wglog:", searchFrom);
            if (start < 0) return resolved;
            int end = resolved.indexOf('@', start + 7);
            if (end < 0) return resolved;
            List<String> lines = messages.get(resolved.substring(start + 7, end));
            if (lines == null || lines.isEmpty()) return "";
            String replacement = String.join(System.lineSeparator(), lines).replace("\\n", System.lineSeparator());
            resolved = resolved.substring(0, start) + replacement + resolved.substring(end + 1);
            searchFrom = start + replacement.length();
        }
    }
}
