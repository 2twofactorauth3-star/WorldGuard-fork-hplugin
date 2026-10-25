package com.sk89q.worldguard.bukkit.integration;

import com.earth2me.essentials.User;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.BukkitPlayer;
import net.ess3.api.IEssentials;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;

final class EssentialsXAdapter implements EssentialsXIntegration.Provider {

    private final IEssentials essentials;

    private EssentialsXAdapter(IEssentials essentials) {
        this.essentials = essentials;
    }

    @Nullable
    static EssentialsXIntegration.Provider create(Plugin dependency) {
        return dependency instanceof IEssentials essentials ? new EssentialsXAdapter(essentials) : null;
    }

    @Override
    @Nullable
    public EssentialsXIntegration.UserState user(LocalPlayer player) {
        Player bukkitPlayer = player instanceof BukkitPlayer local ? local.getPlayer() : Bukkit.getPlayer(player.getUniqueId());
        User user = bukkitPlayer == null ? null : essentials.getUser(bukkitPlayer);
        return user == null ? null : new EssentialsUserState(user);
    }

    private record EssentialsUserState(User user) implements EssentialsXIntegration.UserState {
        @Override
        public boolean current(EssentialsXIntegration.Property property) {
            return property == EssentialsXIntegration.Property.VANISH ? user.isVanished() : user.isGodModeEnabled();
        }

        @Override
        public void set(EssentialsXIntegration.Property property, boolean value) {
            if (property == EssentialsXIntegration.Property.VANISH) {
                user.setVanished(value);
            } else {
                user.setGodModeEnabled(value);
            }
        }
    }
}
