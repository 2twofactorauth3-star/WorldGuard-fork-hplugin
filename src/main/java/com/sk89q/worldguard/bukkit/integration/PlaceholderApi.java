package com.sk89q.worldguard.bukkit.integration;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.plugin.Plugin;

final class PlaceholderApi {

    static PlaceholderIntegration.Resolver create(Plugin dependency) {
        if (PlaceholderAPI.class.getClassLoader() != dependency.getClass().getClassLoader()) {
            throw new IllegalStateException("PlaceholderAPI belongs to another plugin classloader");
        }
        return PlaceholderAPI::setPlaceholders;
    }
}
