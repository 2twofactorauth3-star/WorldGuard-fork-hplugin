package com.sk89q.worldguard.bukkit.integration;

import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class CmiVanishApi implements VanishIntegration.Provider {

    static VanishIntegration.Provider create(Plugin dependency) {
        if (!(dependency instanceof CMI)) {
            throw new IllegalStateException("CMI plugin does not implement its API");
        }
        return new CmiVanishApi();
    }

    @Override
    public boolean current(Player player) {
        return user(player).isCMIVanished();
    }

    @Override
    public void set(Player player, boolean value) {
        user(player).setVanished(value);
    }

    private static CMIUser user(Player player) {
        CMIUser user = CMIUser.getUser(player);
        if (user == null) throw new IllegalStateException("CMI player data is not available");
        return user;
    }
}
