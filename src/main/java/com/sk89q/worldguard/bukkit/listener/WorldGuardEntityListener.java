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
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitWorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.cause.Cause;
import com.sk89q.worldguard.bukkit.util.Entities;
import com.sk89q.worldguard.config.ConfigurationManager;
import com.sk89q.worldguard.config.WorldMechanicSetting;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.FailedLoadRegionSet;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.WindCharge;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Set;

/**
 * Listener for entity related events.
 */
@SuppressWarnings("deprecation")
public class WorldGuardEntityListener extends AbstractListener {

    /**
     * Construct the object;
     *
     * @param plugin The plugin instance
     */
    public WorldGuardEntityListener(WorldGuardPlugin plugin) {
        super(plugin);
    }

    private void onEntityDamageByBlock(EntityDamageByBlockEvent event) {
        Entity defender = event.getEntity();
        DamageCause type = event.getCause();

        WorldConfiguration wcfg = getWorldConfig(defender.getWorld());

        if (defender instanceof Player player && !Entities.isNPC(defender)) {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);

            if (type == DamageCause.BLOCK_EXPLOSION
                    && (blocksMechanic(wcfg.blockOtherExplosions, defender.getLocation())
                            || (wcfg.explosionFlagCancellation
                                && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(localPlayer.getLocation(), (RegionAssociable) null, Flags.OTHER_EXPLOSION))))) {
                event.setCancelled(true);
                return;
            }
        } else {

            // for whatever reason, plugin-caused explosions with a null entity count as block explosions and aren't
            // handled anywhere else
            if (type == DamageCause.BLOCK_EXPLOSION
                    && (blocksMechanic(wcfg.blockOtherExplosions, defender.getLocation())
                            || ((wcfg.explosionFlagCancellation || Entities.isConsideredBuildingIfUsed(defender))
                                && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(BukkitAdapter.adapt(defender.getLocation()), (RegionAssociable) null, Flags.OTHER_EXPLOSION))))) {
                event.setCancelled(true);
                return;

            }
        }
    }

    private void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Projectile) {
            onEntityDamageByProjectile(event);
            return;
        }
        Entity attacker = event.getDamager();
        Entity defender = event.getEntity();
        WorldConfiguration wcfg = getWorldConfig(defender.getWorld());
        if (isProtectedDecorationDamage(attacker, defender, wcfg)
                || isEnderCrystalDamageBlocked(attacker, defender, wcfg)
                || isPlayerDamageBlocked(attacker, defender, wcfg)) {
            event.setCancelled(true);
        }
    }

    private boolean isProtectedDecorationDamage(
            Entity attacker, Entity defender, WorldConfiguration wcfg) {
        if (defender instanceof ItemFrame itemFrame) {
            return checkItemFrameProtection(attacker, itemFrame);
        }
        return defender instanceof ArmorStand
                && !(attacker instanceof Player)
                && blocksMechanic(wcfg.blockEntityArmorStandDestroy, defender.getLocation(),
                        Cause.create(attacker));
    }

    private boolean isEnderCrystalDamageBlocked(
            Entity attacker, Entity defender, WorldConfiguration wcfg) {
        return attacker instanceof EnderCrystal
                && wcfg.useRegions
                && wcfg.explosionFlagCancellation
                && !WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .getApplicableRegions(BukkitAdapter.adapt(defender.getLocation()))
                .testState(null, Flags.OTHER_EXPLOSION);
    }

    private boolean isPlayerDamageBlocked(
            Entity attacker, Entity defender, WorldConfiguration wcfg) {
        if (!(defender instanceof Player player) || Entities.isNPC(defender)) return false;
        if ((attacker instanceof TNTPrimed || attacker instanceof ExplosiveMinecart)
                && blocksMechanic(wcfg.blockTNTExplosions, defender.getLocation(), Cause.create(attacker))) {
            return true;
        }
        if (!(attacker instanceof LivingEntity) || attacker instanceof Player) return false;
        if (attacker instanceof Creeper
                && blocksMechanic(wcfg.blockCreeperExplosions, defender.getLocation(), Cause.create(attacker))) {
            return true;
        }
        if (!wcfg.useRegions || attacker instanceof Tameable) return false;
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        return !WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .getApplicableRegions(localPlayer.getLocation())
                .testState(localPlayer, Flags.MOB_DAMAGE);
    }

    private void onEntityDamageByProjectile(EntityDamageByEntityEvent event) {
        Entity defender = event.getEntity();
        ProjectileSource source = ((Projectile) event.getDamager()).getShooter();
        if (!(source instanceof LivingEntity attacker)) return;
        WorldConfiguration wcfg = getWorldConfig(defender.getWorld());
        if (isProjectilePlayerDamageBlocked(event, attacker, defender, wcfg)
                || defender instanceof ItemFrame itemFrame
                        && checkItemFrameProtection(attacker, itemFrame)
                || defender instanceof ArmorStand
                        && Entities.isNonPlayerCreature(attacker)
                        && blocksMechanic(wcfg.blockEntityArmorStandDestroy,
                                defender.getLocation(), Cause.create(attacker))) {
            event.setCancelled(true);
        }
    }

    private boolean isProjectilePlayerDamageBlocked(
            EntityDamageByEntityEvent event,
            LivingEntity attacker,
            Entity defender,
            WorldConfiguration wcfg) {
        if (!(defender instanceof Player player)
                || Entities.isNPC(defender)
                || attacker instanceof Player) return false;
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        if (wcfg.useRegions && !WorldGuard.getInstance().getPlatform().getRegionContainer()
                .createQuery().getApplicableRegions(localPlayer.getLocation())
                .testState(localPlayer, Flags.MOB_DAMAGE)) return true;
        if (!(event.getDamager() instanceof Fireball fireball)) return false;
        if (blocksFireballDamage(fireball, defender, wcfg)) return true;
        return wcfg.useRegions && wcfg.explosionFlagCancellation
                && !WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .testState(localPlayer.getLocation(), localPlayer,
                        Entities.getExplosionFlag(event.getDamager()));
    }

    private boolean blocksFireballDamage(
            Fireball fireball, Entity defender, WorldConfiguration wcfg) {
        if (fireball instanceof WitherSkull) {
            return blocksMechanic(wcfg.blockWitherSkullExplosions,
                    defender.getLocation(), Cause.create(fireball));
        }
        if (fireball instanceof AbstractWindCharge) {
            return blocksMechanic(wcfg.blockWindChargeExplosions,
                    defender.getLocation(), Cause.create(fireball));
        }
        return blocksMechanic(wcfg.blockFireballExplosions,
                defender.getLocation(), Cause.create(fireball));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {

        if (event instanceof EntityDamageByEntityEvent) {
            this.onEntityDamageByEntity((EntityDamageByEntityEvent) event);
            return;
        } else if (event instanceof EntityDamageByBlockEvent) {
            this.onEntityDamageByBlock((EntityDamageByBlockEvent) event);
            return;
        }

        Entity defender = event.getEntity();
        DamageCause type = event.getCause();

        WorldConfiguration wcfg = getWorldConfig(defender.getWorld());

        if (defender instanceof Player player && !Entities.isNPC(defender)) {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);

            if (type == DamageCause.WITHER) {
                // wither boss DoT tick
                if (wcfg.useRegions) {
                    ApplicableRegionSet set = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(localPlayer.getLocation());

                    if (!set.testState(getPlugin().wrapPlayer(player), Flags.MOB_DAMAGE)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }

        }
    }

    /*
     * Called on entity explode.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity ent = event.getEntity();
        BukkitWorldConfiguration wcfg = getWorldConfig(event.getLocation().getWorld());
        Cause cause = Cause.create(ent);
        if (ent instanceof Creeper) {
            handleCreeperExplosion(event, wcfg, cause);
        } else if (ent instanceof EnderDragon) {
            filterExplosionBlocks(wcfg.blockEnderDragonBlockDamage, event, cause);
        } else if (ent instanceof TNTPrimed || ent instanceof ExplosiveMinecart) {
            handleTntExplosion(event, wcfg, cause);
        } else if (ent instanceof Fireball fireball) {
            handleFireballExplosion(event, wcfg, fireball, cause);
        } else if (ent instanceof Wither) {
            handleWitherExplosion(event, wcfg, cause);
        } else {
            handleOtherExplosion(event, wcfg, cause);
        }
    }

    private void handleCreeperExplosion(
            EntityExplodeEvent event, BukkitWorldConfiguration wcfg, Cause cause) {
        if (blocksExplosion(wcfg.blockCreeperExplosions, event, cause)) {
            event.setCancelled(true);
        } else {
            filterExplosionBlocks(wcfg.blockCreeperBlockDamage, event, cause);
        }
    }

    private void handleTntExplosion(
            EntityExplodeEvent event, BukkitWorldConfiguration wcfg, Cause cause) {
        if (blocksExplosion(wcfg.blockTNTExplosions, event, cause)) {
            event.setCancelled(true);
        } else {
            filterExplosionBlocks(wcfg.blockTNTBlockDamage, event, cause);
        }
    }

    private void handleFireballExplosion(
            EntityExplodeEvent event,
            BukkitWorldConfiguration wcfg,
            Fireball fireball,
            Cause cause) {
        if (isConfiguredFireballExplosionBlocked(event, wcfg, fireball, cause)) return;
        if (wcfg.useRegions && !(fireball instanceof WindCharge)
                && clearBlocksDeniedByFlag(event, Entities.getExplosionFlag(fireball))
                && wcfg.explosionFlagCancellation) {
            event.setCancelled(true);
        }
    }

    private boolean isConfiguredFireballExplosionBlocked(
            EntityExplodeEvent event,
            BukkitWorldConfiguration wcfg,
            Fireball fireball,
            Cause cause) {
        if (fireball instanceof WitherSkull) {
            if (blocksExplosion(wcfg.blockWitherSkullExplosions, event, cause)) {
                event.setCancelled(true);
                return true;
            }
            filterExplosionBlocks(wcfg.blockWitherSkullBlockDamage, event, cause);
            return false;
        }
        if (fireball instanceof AbstractWindCharge) {
            if (!blocksExplosion(wcfg.blockWindChargeExplosions, event, cause)) return false;
            event.setCancelled(true);
            return true;
        }
        if (blocksExplosion(wcfg.blockFireballExplosions, event, cause)) {
            event.setCancelled(true);
            return true;
        }
        filterExplosionBlocks(wcfg.blockFireballBlockDamage, event, cause);
        return false;
    }

    private void handleWitherExplosion(
            EntityExplodeEvent event, BukkitWorldConfiguration wcfg, Cause cause) {
        if (blocksExplosion(wcfg.blockWitherExplosions, event, cause)) {
            event.setCancelled(true);
            return;
        }
        filterExplosionBlocks(wcfg.blockWitherBlockDamage, event, cause);
        if (wcfg.useRegions && clearBlocksDeniedByFlag(event, Flags.WITHER_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    private void handleOtherExplosion(
            EntityExplodeEvent event, BukkitWorldConfiguration wcfg, Cause cause) {
        if (blocksExplosion(wcfg.blockOtherExplosions, event, cause)) {
            event.setCancelled(true);
            return;
        }
        if (wcfg.useRegions && clearBlocksDeniedByFlag(event, Flags.OTHER_EXPLOSION)
                && wcfg.explosionFlagCancellation) {
            event.setCancelled(true);
        }
    }

    private boolean clearBlocksDeniedByFlag(EntityExplodeEvent event, StateFlag flag) {
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        for (Block block : event.blockList()) {
            if (!query.testState(BukkitAdapter.adapt(block.getLocation()), null, flag)) {
                event.blockList().clear();
                return true;
            }
        }
        return false;
    }

    /*
     * Called on explosion prime
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        Entity ent = event.getEntity();

        BukkitWorldConfiguration wcfg = getWorldConfig(ent.getWorld());
        if (event.getEntityType() == EntityType.WITHER) {
            if (blocksMechanic(wcfg.blockWitherExplosions, ent.getLocation(), Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }
        } else if (event.getEntityType() == EntityType.WITHER_SKULL) {
            if (blocksMechanic(wcfg.blockWitherSkullExplosions, ent.getLocation(), Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }
        } else if (event.getEntityType() == EntityType.FIREBALL) {
            if (blocksMechanic(wcfg.blockFireballExplosions, ent.getLocation(), Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }
        } else if (event.getEntityType() == EntityType.CREEPER) {
            if (blocksMechanic(wcfg.blockCreeperExplosions, ent.getLocation(), Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }
        } else if (event.getEntityType() == EntityType.TNT
                || event.getEntityType() == EntityType.TNT_MINECART) {
            if (blocksMechanic(wcfg.blockTNTExplosions, ent.getLocation(), Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }
        } else if (event.getEntity() instanceof AbstractWindCharge) {
            if (blocksMechanic(wcfg.blockWindChargeExplosions, ent.getLocation(), Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        ConfigurationManager cfg = getConfig();
        WorldConfiguration wcfg = getWorldConfig(event.getEntity().getWorld());

        if (!wcfg.blockPluginSpawning && Entities.isPluginSpawning(event.getSpawnReason())) {
            return;
        }

        // armor stands are living entities, but we check them as blocks/non-living entities, so ignore them here
        if (Entities.isConsideredBuildingIfUsed(event.getEntity())) {
            return;
        }

        EntityType entityType = event.getEntityType();

        com.sk89q.worldedit.world.entity.EntityType weEntityType = BukkitAdapter.adapt(entityType);

        Location eventLoc = event.getLocation();

        RegionManager regionManager = getRegionManager(eventLoc.getWorld());
        if (wcfg.useRegions && cfg.useRegionsCreatureSpawnEvent
                && regionManager != null
                && (regionManager.hasState(Flags.MOB_SPAWNING, State.DENY)
                || regionManager.hasFlag(Flags.DENY_SPAWN))) {
            ApplicableRegionSet set =
                    WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(BukkitAdapter.adapt(eventLoc));

            if (!set.testState(null, Flags.MOB_SPAWNING)) {
                event.setCancelled(true);
                return;
            }

            Set<com.sk89q.worldedit.world.entity.EntityType> entityTypes = set.queryValue(null, Flags.DENY_SPAWN);
            if (entityTypes != null && weEntityType != null && entityTypes.contains(weEntityType)) {
                event.setCancelled(true);
                return;
            }
        }

        if (entityType == EntityType.SLIME
                && eventLoc.getY() >= 60
                && event.getSpawnReason() == SpawnReason.NATURAL
                && blocksMechanic(wcfg.blockGroundSlimes, eventLoc)) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatePortal(PortalCreateEvent event) {
        WorldConfiguration wcfg = getWorldConfig(event.getWorld());

        if (wcfg.useRegions && wcfg.regionNetherPortalProtection
                && event.getReason() == PortalCreateEvent.CreateReason.NETHER_PAIR
                && !event.getBlocks().isEmpty()) {
            final com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(event.getWorld());
            final Cause cause = Cause.create(event.getEntity());
            LocalPlayer localPlayer = null;
            if (cause.getRootCause() instanceof Player player) {
                localPlayer = getPlugin().wrapPlayer(player);
                if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, world)) {
                    return;
                }
            }
            final RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(world);
            ApplicableRegionSet regions;
            if (regionManager == null) {
                regions = FailedLoadRegionSet.getInstance();
            } else {
                BlockVector3 min = null;
                BlockVector3 max = null;
                for (BlockState block : event.getBlocks()) {
                    BlockVector3 loc = BlockVector3.at(block.getX(), block.getY(), block.getZ());
                    min = min == null ? loc : loc.getMinimum(min);
                    max = max == null ? loc : loc.getMaximum(max);
                }
                ProtectedCuboidRegion target = new ProtectedCuboidRegion("__portal_check", true, min, max);
                regions = regionManager.getApplicableRegions(target);
            }
            final RegionAssociable associable = createRegionAssociable(cause);
            final State buildState = StateFlag.denyToNone(regions.queryState(associable, Flags.BUILD));
            if (!StateFlag.test(buildState, regions.queryState(associable, Flags.BLOCK_BREAK))
                    || !StateFlag.test(buildState, regions.queryState(associable, Flags.BLOCK_PLACE))) {
                if (localPlayer != null && !cause.isIndirect()) {
                    // NB there is no way to cancel the teleport without PTA (since PlayerPortal doesn't have block info)
                    // removing PTA was a mistake
                    String message = regions.queryValue(localPlayer, Flags.DENY_MESSAGE);
                    RegionProtectionListener.formatAndSendDenyMessage(
                            "@wg:protectionActionCreatePortals@", localPlayer, message);
                }
                event.setCancelled(true);
            }
        }

        // NOTE: as of right now, bukkit doesn't fire this event for this (despite deprecating EntityCreatePortalEvent for it)
        // maybe one day this code will be useful
        if (event.getEntity() instanceof EnderDragon
                && blocksMechanic(wcfg.blockEnderDragonPortalCreation,
                        event.getEntity().getLocation(), Cause.create(event.getEntity()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityRegainHealth(EntityRegainHealthEvent event) {
        RegainReason regainReason = event.getRegainReason();
        if (regainReason != RegainReason.REGEN && regainReason != RegainReason.SATIATED) {
            return;
        }

        Entity ent = event.getEntity();

        WorldConfiguration wcfg = getWorldConfig(ent.getWorld());

        RegionManager manager = getRegionManager(ent.getWorld());
        if (wcfg.useRegions && manager != null && manager.hasState(Flags.HEALTH_REGEN, State.DENY)
                && ent instanceof Player player && !Entities.isNPC(ent)
                && !WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().testState(
                        BukkitAdapter.adapt(ent.getLocation()),
                        WorldGuardPlugin.inst().wrapPlayer(player),
                        Flags.HEALTH_REGEN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getItem() != null) return;
        HumanEntity ent = event.getEntity();
        if (Entities.isNPC(ent)) return;
        if (!(ent instanceof Player bukkitPlayer)) return;
        if (event.getFoodLevel() > ent.getFoodLevel()) return;

        LocalPlayer player = WorldGuardPlugin.inst().wrapPlayer(bukkitPlayer);
        WorldConfiguration wcfg = getWorldConfig(ent.getWorld());
        RegionManager manager = getRegionManager(ent.getWorld());

        if (wcfg.useRegions && manager != null && manager.hasState(Flags.HUNGER_DRAIN, State.DENY)
                && !WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().testState(
                        player.getLocation(), player, Flags.HUNGER_DRAIN)) {
            event.setCancelled(true);
        }
    }

    /**
     * Called when an entity changes a block somehow
     *
     * @param event Relevant event details
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Entity ent = event.getEntity();

        WorldConfiguration wcfg = getWorldConfig(ent.getWorld());
        if (ent instanceof FallingBlock) {
            Material id = event.getBlock().getType();

            if (id == Material.GRAVEL
                    && blocksMechanic(wcfg.noPhysicsGravel, event.getBlock().getLocation(),
                            Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }

            if ((id == Material.SAND || id == Material.RED_SAND)
                    && blocksMechanic(wcfg.noPhysicsSand, event.getBlock().getLocation(),
                            Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }
        } else if (ent instanceof Enderman) {
            if (blocksMechanic(wcfg.disableEndermanGriefing, event.getBlock().getLocation(),
                    Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }
        } else if (ent.getType() == EntityType.WITHER) {
            if (blocksMechanic(wcfg.blockWitherBlockDamage, event.getBlock().getLocation(), Cause.create(ent))
                    || blocksMechanic(wcfg.blockWitherExplosions, event.getBlock().getLocation(),
                            Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }
            if (wcfg.useRegions) {
                Location location = event.getBlock().getLocation();
                if (!StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().queryState(BukkitAdapter.adapt(location), (RegionAssociable) null, Flags.WITHER_DAMAGE))) {
                    event.setCancelled(true);
                    return;
                }
            }
        } else if (/*ent instanceof Zombie && */event instanceof EntityBreakDoorEvent) {
            if (blocksMechanic(wcfg.blockZombieDoorDestruction, event.getBlock().getLocation(),
                    Cause.create(ent))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        BukkitWorldConfiguration wcfg = getWorldConfig(event.getEntered().getWorld());

        if (!(event.getEntered() instanceof Player)
                && blocksMechanic(wcfg.blockEntityVehicleEntry, event.getVehicle().getLocation(),
                        Cause.create(event.getEntered()))) {
            event.setCancelled(true);
        }
    }

    /**
     * Checks regions and config settings to protect items from being knocked
     * out of item frames.
     * @param attacker attacking entity
     * @param defender item frame being damaged
     * @return true if the event should be cancelled
     */
    private boolean checkItemFrameProtection(Entity attacker, ItemFrame defender) {
        World world = defender.getWorld();
        WorldConfiguration wcfg = getWorldConfig(world);
        if (wcfg.useRegions) {
            // bukkit throws this event when a player attempts to remove an item from a frame
            if (!(attacker instanceof Player)) {
                if (!StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().queryState(BukkitAdapter.adapt(defender.getLocation()), (RegionAssociable) null, Flags.ENTITY_ITEM_FRAME_DESTROY))) {
                    return true;
                }
            }
        }
        return !(attacker instanceof Player)
                && blocksMechanic(wcfg.blockEntityItemFrameDestroy, defender.getLocation(),
                        Cause.create(attacker));
    }

    private boolean blocksExplosion(
            WorldMechanicSetting setting,
            EntityExplodeEvent event,
            Cause cause
    ) {
        if (blocksMechanic(setting, event.getLocation(), cause)) {
            return true;
        }
        for (Block block : event.blockList()) {
            if (blocksMechanic(setting, block.getLocation(), cause)) {
                return true;
            }
        }
        return false;
    }

    private void filterExplosionBlocks(
            WorldMechanicSetting setting,
            EntityExplodeEvent event,
            Cause cause
    ) {
        event.blockList().removeIf(block -> blocksMechanic(setting, block.getLocation(), cause));
    }

}
