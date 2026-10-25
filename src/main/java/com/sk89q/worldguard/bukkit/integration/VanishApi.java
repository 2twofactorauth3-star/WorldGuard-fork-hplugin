package com.sk89q.worldguard.bukkit.integration;

import de.myzelyam.api.vanish.VanishAPI;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class VanishApi implements VanishIntegration.Provider {

    static VanishIntegration.Provider create(Plugin dependency) {
        if (VanishAPI.class.getClassLoader() != dependency.getClass().getClassLoader()) {
            throw new IllegalStateException("Vanish API belongs to another plugin classloader");
        }
        return new VanishApi();
    }

    @Override
    public boolean current(Player player) {
        return VanishAPI.isInvisible(player);
    }

    @Override
    public void set(Player player, boolean value) {
        if (value) {
            VanishAPI.hidePlayer(player);
        } else {
            VanishAPI.showPlayer(player);
        }
    }
}
