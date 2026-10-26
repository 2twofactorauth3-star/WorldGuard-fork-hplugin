package com.sk89q.worldguard.bukkit.integration;

import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.session.AbstractSessionManager;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.util.function.Function;
import java.util.function.Supplier;

final class IntegrationPlayerRefresh<B> {

    private final Plugin owner;
    private final Function<Player, LocalPlayer> playerWrapper;
    private final Supplier<B> binding;
    private final SessionRefresh refresh;

    IntegrationPlayerRefresh(Plugin owner, Function<Player, LocalPlayer> playerWrapper,
                             Supplier<B> binding, SessionRefresh refresh) {
        this.owner = owner;
        this.playerWrapper = playerWrapper;
        this.binding = binding;
        this.refresh = refresh;
    }

    void refresh(@Nullable AbstractSessionManager manager, @Nullable B expected) {
        if (manager == null || !owner.isEnabled()) return;
        for (Player player : owner.getServer().getOnlinePlayers()) {
            Runnable task = () -> refreshPlayer(player, manager, expected);
            if (owner instanceof WorldGuardPlugin plugin && plugin.isFolia()) {
                player.getScheduler().run(owner, ignored -> task.run(), null);
            } else {
                owner.getServer().getScheduler().runTask(owner, task);
            }
        }
    }

    private void refreshPlayer(Player player, AbstractSessionManager manager, @Nullable B expected) {
        if (binding.get() != expected || !owner.isEnabled() || !player.isOnline()) return;
        LocalPlayer local = playerWrapper.apply(player);
        Session session = expected == null ? manager.getIfPresent(local) : manager.get(local);
        if (session == null) return;
        Location location = local.getLocation();
        if (expected != null) session.testMoveTo(local, location, MoveType.OTHER_NON_CANCELLABLE);
        ApplicableRegionSet regions = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().createQuery().getApplicableRegions(location);
        refresh.apply(session, local, location, regions);
    }

    interface SessionRefresh {
        void apply(Session session, LocalPlayer player, Location location, ApplicableRegionSet regions);
    }
}
