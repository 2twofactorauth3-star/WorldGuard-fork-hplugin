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

package com.sk89q.worldguard.bukkit.listener;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitRegionContainer;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.session.BukkitSessionManager;
import com.sk89q.worldguard.bukkit.util.Entities;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@SuppressWarnings("deprecation")
public class PlayerMoveListener extends AbstractListener {

    private static volatile Set<com.sk89q.worldguard.protection.flags.Flag<?>> movementFlags = Set.of(
            Flags.ENTRY,
            Flags.EXIT,
            Flags.EXIT_OVERRIDE,
            Flags.GREET_MESSAGE,
            Flags.GREET_TITLE,
            Flags.FAREWELL_MESSAGE,
            Flags.FAREWELL_TITLE,
            Flags.GAME_MODE,
            Flags.TIME_LOCK,
            Flags.WEATHER_LOCK
    );
    private static Set<com.sk89q.worldguard.protection.flags.Flag<?>> registeredMovementFlags = movementFlags;
    private static final Map<Object, Set<com.sk89q.worldguard.protection.flags.Flag<?>>> movementFlagOwners =
            new IdentityHashMap<>();
    private static final AtomicLong MOVEMENT_FLAGS_REVISION = new AtomicLong();
    private final ThreadLocal<MovementInterestCache> movementInterestCache =
            ThreadLocal.withInitial(MovementInterestCache::new);

    public static synchronized void registerMovementFlag(
            com.sk89q.worldguard.protection.flags.Flag<?> flag) {
        java.util.HashSet<com.sk89q.worldguard.protection.flags.Flag<?>> updated =
                new java.util.HashSet<>(registeredMovementFlags);
        updated.add(flag);
        registeredMovementFlags = Set.copyOf(updated);
        refreshMovementFlags();
    }

    public static synchronized void setMovementFlags(Object owner,
            Set<? extends com.sk89q.worldguard.protection.flags.Flag<?>> flags) {
        if (flags.isEmpty()) {
            movementFlagOwners.remove(owner);
        } else {
            movementFlagOwners.put(owner, Set.copyOf(flags));
        }
        refreshMovementFlags();
    }

    private static void refreshMovementFlags() {
        java.util.HashSet<com.sk89q.worldguard.protection.flags.Flag<?>> updated =
                new java.util.HashSet<>(registeredMovementFlags);
        for (Set<com.sk89q.worldguard.protection.flags.Flag<?>> flags : movementFlagOwners.values()) updated.addAll(flags);
        Set<com.sk89q.worldguard.protection.flags.Flag<?>> active = Set.copyOf(updated);
        if (movementFlags.equals(active)) return;
        movementFlags = active;
        MOVEMENT_FLAGS_REVISION.incrementAndGet();
    }

    public PlayerMoveListener(WorldGuardPlugin plugin) {
        super(plugin);
    }

    @Override
    public void registerEvents() {
        PluginManager pm = getPlugin().getServer().getPluginManager();
        pm.registerEvents(this, getPlugin());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        LocalPlayer player = getPlugin().wrapPlayer(event.getPlayer());

        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);
        session.testMoveTo(player, BukkitAdapter.adapt(event.getRespawnLocation()), MoveType.RESPAWN, true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        if (event.isFlying() && !isFlightAllowed(event.getPlayer(), event.getPlayer().getLocation(), Flags.FLY)) {
            event.setCancelled(true);
            disableFlight(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityToggleGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && event.getEntity() instanceof Player player
                && !isFlightAllowed(player, player.getLocation(), Flags.ELYTRA)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleportFlight(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            enforceFlightFlags(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        Entity entity = event.getEntered();
        if (entity instanceof Player bukkitPlayer) {
            LocalPlayer player = getPlugin().wrapPlayer(bukkitPlayer);
            Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);
            if (null != session.testMoveTo(player, BukkitAdapter.adapt(event.getVehicle().getLocation()), MoveType.EMBARK, true)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld().equals(to.getWorld()) && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        final Player player = event.getPlayer();
        getPlugin().requestTerritoryUpdate(player);
        BukkitRegionContainer container = (BukkitRegionContainer) WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regionManager = container.get(player.getWorld());
        if (!requiresMovementQuery(player, regionManager)) {
            return;
        }
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);

        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer);
        MoveType moveType = MoveType.MOVE;
        if (player.isGliding()) {
            moveType = MoveType.GLIDE;
        } else if (player.isSwimming()) {
            moveType = MoveType.SWIM;
        } else if (player.getVehicle() instanceof AbstractHorse) {
            moveType = MoveType.RIDE;
        }
        com.sk89q.worldedit.util.Location weLocation = session.testMoveTo(localPlayer, container.adapt(to), moveType);

        if (weLocation != null) {
            final Location override = BukkitAdapter.adapt(weLocation);
            override.setX(override.getBlockX() + 0.5);
            override.setY(override.getBlockY());
            override.setZ(override.getBlockZ() + 0.5);
            override.setPitch(to.getPitch());
            override.setYaw(to.getYaw());

            event.setTo(override.clone());

            Entity vehicle = player.getVehicle();
            if (vehicle != null) {
                vehicle.eject();

                Entity current = vehicle;
                while (current != null) {
                    current.eject();
                    vehicle.setVelocity(new Vector());
                    if (vehicle instanceof LivingEntity) {
                        Location vehicleTeleportLocation = override.clone();
                        teleport(vehicle, vehicleTeleportLocation);
                    } else {
                        Location dismountLocation = override.clone().add(0, 1, 0);
                        teleport(vehicle, dismountLocation);
                    }
                    current = current.getVehicle();
                }

                Location playerDismountLocation = override.clone().add(0, 1, 0);
                teleport(player, playerDismountLocation);


                Location delayedDismountLocation = override.clone().add(0, 1, 0);
                Runnable task = () -> teleport(player, delayedDismountLocation);
                if (getPlugin().isFolia()) {
                    player.getScheduler().runDelayed(getPlugin(), new Consumer() {
                        @Override
                        public void accept(Object ignored) {
                            task.run();
                        }
                    }, null, 1);
                } else {
                    Bukkit.getScheduler().runTaskLater(getPlugin(), task, 1);
                }
            }
        }

        if (weLocation == null) {
            getPlugin().cacheTerritoryRegions(player, to, regionManager, session.getLastApplicableRegionSet());
            enforceFlightFlags(player, localPlayer, session.getLastApplicableRegionSet());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        getPlugin().clearClaimExpansionPreference(player.getUniqueId());
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);

        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer);
        com.sk89q.worldedit.util.Location loc = session.testMoveTo(localPlayer,
            BukkitAdapter.adapt(event.getPlayer().getLocation()), MoveType.OTHER_CANCELLABLE); // white lie
        if (loc != null) {
            if (getPlugin().isFolia()) {
                player.teleportAsync(BukkitAdapter.adapt(loc));
            } else {
                player.teleport(BukkitAdapter.adapt(loc));
            }
        }

        session.uninitialize(localPlayer);
        ((BukkitSessionManager) WorldGuard.getInstance().getPlatform()
                .getSessionManager()).remove(localPlayer);
        getPlugin().forgetPlayer(player);
    }

    /**
     * Teleport an entity using Paper's asynchronous API.
     *
     * @param entity The entity to teleport
     * @param location The location to teleport to
     */
    private void teleport(Entity entity, Location location) {
        entity.teleportAsync(location);
    }

    private boolean isFlightAllowed(Player player, Location location, StateFlag flag) {
        if (Entities.isNPC(player)) {
            return true;
        }
        RegionManager manager = ((BukkitRegionContainer) WorldGuard.getInstance().getPlatform()
                .getRegionContainer()).get(location.getWorld());
        if (manager == null || !manager.hasState(flag, StateFlag.State.DENY)) {
            return true;
        }

        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(localPlayer, BukkitAdapter.adapt(location.getWorld()))) {
            return true;
        }

        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        return query.testState(BukkitAdapter.adapt(location), localPlayer, flag);
    }

    private void enforceFlightFlags(Player player, Location location) {
        boolean flying = hasFlight(player);
        boolean gliding = player.isGliding();
        if (Entities.isNPC(player) || !flying && !gliding) {
            return;
        }
        RegionManager manager = ((BukkitRegionContainer) WorldGuard.getInstance().getPlatform()
                .getRegionContainer()).get(location.getWorld());
        if (manager == null) {
            return;
        }
        MovementInterest interest = movementInterestCache.get().get(manager);
        boolean checkFly = flying && interest.flyDeny;
        boolean checkElytra = gliding && interest.elytraDeny;
        if (!checkFly && !checkElytra) {
            return;
        }

        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(localPlayer, BukkitAdapter.adapt(location.getWorld()))) {
            return;
        }

        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        com.sk89q.worldedit.util.Location target = BukkitAdapter.adapt(location);
        if (checkFly && !query.testState(target, localPlayer, Flags.FLY)) {
            disableFlight(player);
        }
        if (checkElytra && !query.testState(target, localPlayer, Flags.ELYTRA)) {
            player.setGliding(false);
        }
    }

    private static void enforceFlightFlags(Player player, LocalPlayer localPlayer, ApplicableRegionSet regions) {
        if (regions == null || Entities.isNPC(player)) {
            return;
        }
        boolean denyFly = hasFlight(player) && !regions.testState(localPlayer, Flags.FLY);
        boolean denyElytra = player.isGliding() && !regions.testState(localPlayer, Flags.ELYTRA);
        if ((!denyFly && !denyElytra) || WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(localPlayer, localPlayer.getWorld())) {
            return;
        }
        if (denyFly) {
            disableFlight(player);
        }
        if (denyElytra) {
            player.setGliding(false);
        }
    }

    private static boolean hasFlight(Player player) {
        return player.getAllowFlight() || player.isFlying();
    }

    private static void disableFlight(Player player) {
        if (player.isFlying()) {
            player.setFlying(false);
        }
        if (player.getAllowFlight()) {
            player.setAllowFlight(false);
        }
    }

    private boolean requiresMovementQuery(Player player, RegionManager manager) {
        if (manager == null) {
            return false;
        }
        MovementInterest interest = movementInterestCache.get().get(manager);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().customHandlersRegistered()
                || interest.boundary) {
            return true;
        }
        return interest.flyDeny && hasFlight(player)
                || interest.elytraDeny && player.isGliding();
    }

    private static final class MovementInterestCache {

        private final Map<RegionManager, InterestEntry> worlds = new WeakHashMap<>();
        private RegionManager manager;
        private long flagRevision = -1;
        private long queryRevision = -1;
        private long movementFlagsRevision = -1;
        private MovementInterest interest;

        private MovementInterest get(RegionManager currentManager) {
            long currentFlagRevision = ProtectedRegion.flagRevision();
            long currentQueryRevision = currentManager.getQueryRevision();
            long currentMovementFlagsRevision = MOVEMENT_FLAGS_REVISION.get();
            if (manager == currentManager && flagRevision == currentFlagRevision
                    && queryRevision == currentQueryRevision && movementFlagsRevision == currentMovementFlagsRevision) {
                return interest;
            }
            InterestEntry current = worlds.get(currentManager);
            if (current == null || current.flagRevision != currentFlagRevision
                    || current.queryRevision != currentQueryRevision
                    || current.movementFlagsRevision != currentMovementFlagsRevision) {
                current = new InterestEntry(currentFlagRevision, currentQueryRevision, currentMovementFlagsRevision,
                        new MovementInterest(
                        currentManager.hasAnyFlag(movementFlags),
                        currentManager.hasState(Flags.FLY, StateFlag.State.DENY),
                        currentManager.hasState(Flags.ELYTRA, StateFlag.State.DENY)));
                worlds.put(currentManager, current);
            }
            manager = currentManager;
            flagRevision = current.flagRevision;
            queryRevision = current.queryRevision;
            movementFlagsRevision = current.movementFlagsRevision;
            interest = current.interest;
            return interest;
        }
    }

    private record InterestEntry(long flagRevision, long queryRevision, long movementFlagsRevision,
                                 MovementInterest interest) {
    }

    private record MovementInterest(boolean boundary, boolean flyDeny, boolean elytraDeny) {
    }

    @EventHandler
    public void onEntityMount(EntityMountEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player bukkitPlayer) {
            LocalPlayer player = getPlugin().wrapPlayer(bukkitPlayer);
            Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);
            if (null != session.testMoveTo(player, BukkitAdapter.adapt(event.getMount().getLocation()), MoveType.EMBARK, true)) {
                event.setCancelled(true);
            }
        }
    }
}
