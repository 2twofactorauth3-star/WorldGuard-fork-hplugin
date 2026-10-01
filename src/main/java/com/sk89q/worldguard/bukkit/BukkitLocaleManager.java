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

import com.sk89q.util.yaml.YAMLFormat;
import com.sk89q.util.yaml.YAMLProcessor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Installs and tracks the English or Russian resource set.
 */
final class BukkitLocaleManager {

    private static final Set<String> SUPPORTED_LOCALES = Set.of("en", "ru");
    private static final List<String> LOCALIZED_FILES =
            List.of("config.yml", "messages.yml", "logs.yml", "regionDefaults.yml");
    private static final String PENDING_FALLBACK_FILE = ".locale-pending";

    private final WorldGuardPlugin plugin;
    private String currentLocale = "en";
    private volatile ConfigValues configValues;

    BukkitLocaleManager(WorldGuardPlugin plugin) {
        this.plugin = plugin;
    }

    boolean prepareOnEnable() {
        createDataFolder();
        if (choiceFile().isFile()) {
            return installSelectedLocale();
        }
        if (!installedLocaleFile().isFile()) {
            writeChoiceFile();
            recordPendingFallbackFiles();
            plugin.getLogger().warning("@wglog:localeChoiceCreated@");
            return false;
        }
        if (activateLocale(readInstalledLocale(), Set.of())) {
            return true;
        }
        writeChoiceFile();
        recordPendingFallbackFiles();
        plugin.getLogger().warning("@wglog:localeChoiceCreated@");
        return false;
    }

    String currentLocale() {
        return currentLocale;
    }

    void installRuntimeFallback() {
        for (String name : LOCALIZED_FILES) {
            copyLocalizedFile(name, false);
        }
    }

    InputStream openDefaultResource(String name) {
        return plugin.getResource("locale/" + currentLocale + "/" + name);
    }

    String configuredPrefix() {
        return configuredValues().prefix;
    }

    int configuredActionBarMultipleLinesMode() {
        return configuredValues().actionBarMultipleLinesMode;
    }

    void invalidateConfigValues() {
        configValues = null;
    }

    private ConfigValues configuredValues() {
        ConfigValues cached = configValues;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = configValues;
            if (cached != null) {
                return cached;
            }

            YAMLProcessor yaml = new YAMLProcessor(configFile(), false, YAMLFormat.EXTENDED);
            String prefix = "";
            int actionBarMode = 2;
            try {
                yaml.load();
                prefix = yaml.getString("settings.prefix", "");
                Object configuredMode = yaml.getProperty("settings.messages.actionbarMultipleLinesMode");
                if (configuredMode instanceof Number number
                        && (number.intValue() == 1 || number.intValue() == 2)) {
                    actionBarMode = number.intValue();
                } else if (configuredMode != null) {
                    plugin.getLogger().warning(
                            "@wglog:configInvalidActionbarMultipleLinesMode@" + configuredMode);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "@wglog:logErrorReadingConfigurationForGlobalConfig@", e);
            }
            cached = new ConfigValues(prefix == null ? "" : prefix, actionBarMode);
            configValues = cached;
            return cached;
        }
    }

    private boolean installSelectedLocale() {
        String selected = readYamlValue(choiceFile(), "locale");
        if (selected == null || selected.isBlank()) {
            selected = readYamlValue(choiceFile(), "install");
        }
        if (selected == null || selected.isBlank()) {
            recordPendingFallbackFiles();
            plugin.getLogger().warning("@wglog:localeNotSelected@");
            return false;
        }

        String normalized = normalize(selected);
        if (normalized == null) {
            recordPendingFallbackFiles();
            plugin.getLogger().warning("@wglog:localeUnsupported@" + selected);
            return false;
        }

        Set<String> replaceFiles = readPendingFallbackFiles();
        if (!activateLocale(normalized, replaceFiles)) {
            return false;
        }
        writeInstalledLocale(normalized);
        deleteChoiceFile();
        clearPendingFallbackFiles();
        return true;
    }

    private boolean activateLocale(String locale, Set<String> replaceFiles) {
        String normalized = normalize(locale);
        if (normalized == null) {
            plugin.getLogger().warning("@wglog:localeUnsupported@" + locale);
            return false;
        }

        currentLocale = normalized;
        for (String name : LOCALIZED_FILES) {
            copyLocalizedFile(name, replaceFiles.contains(name));
        }
        return true;
    }

    private void copyLocalizedFile(String name, boolean replaceExisting) {
        File target = new File(plugin.getDataFolder(), name);
        if (target.isFile() && !replaceExisting) {
            return;
        }

        try (InputStream input = localeResource(currentLocale, name)) {
            if (input == null) {
                throw new IllegalStateException("@wglog:missingLocaleFile@" + name);
            }
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("@wglog:unableSwitchLocaleFile@" + name, e);
        }
    }

    private void recordPendingFallbackFiles() {
        Set<String> pending = new LinkedHashSet<>(readPendingFallbackFiles());
        for (String name : LOCALIZED_FILES) {
            if (!new File(plugin.getDataFolder(), name).isFile()) {
                pending.add(name);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        try {
            Files.write(pendingFallbackFile().toPath(), pending, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "@wglog:unableSwitchLocaleFile@" + PENDING_FALLBACK_FILE, e);
        }
    }

    private Set<String> readPendingFallbackFiles() {
        File marker = pendingFallbackFile();
        if (!marker.isFile()) {
            return Set.of();
        }
        try {
            return new LinkedHashSet<>(
                    Files.readAllLines(marker.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "@wglog:unableReadLocaleFrom@" + marker, e);
            return Set.of();
        }
    }

    private void clearPendingFallbackFiles() {
        try {
            Files.deleteIfExists(pendingFallbackFile().toPath());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "@wglog:unableSwitchLocaleFile@" + PENDING_FALLBACK_FILE, e);
        }
    }

    private InputStream localeResource(String locale, String name) {
        return plugin.getResource("locale/" + locale + "/" + name);
    }

    private String readYamlValue(File file, String path) {
        YAMLProcessor yaml = new YAMLProcessor(file, false, YAMLFormat.EXTENDED);
        try {
            yaml.load();
            return yaml.getString(path, "");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "@wglog:unableReadLocaleFrom@" + file, e);
            return null;
        }
    }

    private String readInstalledLocale() {
        try {
            return Files.readString(installedLocaleFile().toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("@wglog:unableReadLocaleFrom@" + installedLocaleFile(), e);
        }
    }

    private static String normalize(String locale) {
        if (locale == null) return null;
        String normalized = locale.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_LOCALES.contains(normalized) ? normalized : null;
    }

    private void createDataFolder() {
        File folder = plugin.getDataFolder();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IllegalStateException("Unable to create " + folder);
        }
    }

    private void writeChoiceFile() {
        try (InputStream input = plugin.getResource("locale.yml")) {
            if (input == null) {
                throw new IllegalStateException("@wglog:missingLocaleFile@locale.yml");
            }
            Files.copy(input, choiceFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("@wglog:unableSwitchLocaleFile@locale.yml", e);
        }
    }

    private void writeInstalledLocale(String locale) {
        try {
            Files.writeString(installedLocaleFile().toPath(), locale, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("@wglog:unableSwitchLocaleFile@.locale", e);
        }
    }

    private void deleteChoiceFile() {
        try {
            Files.deleteIfExists(choiceFile().toPath());
        } catch (IOException e) {
            throw new IllegalStateException("@wglog:unableSwitchLocaleFile@locale.yml", e);
        }
    }

    private File choiceFile() {
        return new File(plugin.getDataFolder(), "locale.yml");
    }

    private File installedLocaleFile() {
        return new File(plugin.getDataFolder(), ".locale");
    }

    private File pendingFallbackFile() {
        return new File(plugin.getDataFolder(), PENDING_FALLBACK_FILE);
    }

    private File configFile() {
        return new File(plugin.getDataFolder(), "config.yml");
    }

    private static final class ConfigValues {
        public final String prefix;
        public final int actionBarMultipleLinesMode;

        private ConfigValues(String prefix, int actionBarMultipleLinesMode) {
            this.prefix = prefix;
            this.actionBarMultipleLinesMode = actionBarMultipleLinesMode;
        }
    }

}
