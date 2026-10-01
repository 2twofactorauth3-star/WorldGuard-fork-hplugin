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

import com.google.common.base.Predicate;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitMessages;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.cause.Cause;
import com.sk89q.worldguard.bukkit.event.DelegateEvent;
import com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent;
import com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent;
import com.sk89q.worldguard.bukkit.event.block.UseBlockEvent;
import com.sk89q.worldguard.bukkit.event.entity.DamageEntityEvent;
import com.sk89q.worldguard.bukkit.event.entity.DestroyEntityEvent;
import com.sk89q.worldguard.bukkit.event.entity.SpawnEntityEvent;
import com.sk89q.worldguard.bukkit.event.entity.UseEntityEvent;
import com.sk89q.worldguard.bukkit.internal.WGMetadata;
import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;
import com.sk89q.worldguard.bukkit.util.Entities;
import com.sk89q.worldguard.bukkit.util.Events;
import com.sk89q.worldguard.bukkit.util.Materials;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Event;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handle events that need to be processed by region protection.
 */
@SuppressWarnings("deprecation")
public class RegionProtectionListener extends AbstractListener {

    private static final String DISEMBARK_MESSAGE_KEY = "worldguard.region.disembarkMessage";
    private static final int LAST_MESSAGE_DELAY = 500;

    /**
     * Construct the listener.
     *
     * @param plugin an instance of WorldGuardPlugin
     */
    public RegionProtectionListener(WorldGuardPlugin plugin) {
        super(plugin);
    }

    /**
     * Tell a sender that s/he cannot do something 'here'.
     *
     * @param event the event
     * @param cause the cause
     * @param location the location
     * @param what what was done
     */
    private void tellErrorMessage(DelegateEvent event, Cause cause, Location location, String what) {
        if (event.isSilent() || cause.isIndirect()) {
            return;
        }

        Object rootCause = cause.getRootCause();

        if (rootCause instanceof Player player) {
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
            String message = query.queryValue(BukkitAdapter.adapt(location), localPlayer, Flags.DENY_MESSAGE);
            formatAndSendDenyMessage(what, localPlayer, message);
        }
    }

    static void formatAndSendDenyMessage(String what, LocalPlayer localPlayer, String message) {
        if (message == null || message.isEmpty()) return;
        message = WorldGuard.getInstance().getPlatform().getMatcher().replaceMacros(localPlayer, message);
        if (message.equals(Flags.DENY_MESSAGE.getDefault())) {
            message = deniedMessage(what);
        } else {
            message = BukkitMessages.replacePlaceholder(message, "what", what);
        }
        localPlayer.print(TextComponent.of(message));
    }

    private static String deniedMessage(String action) {
        String actionPrefix = "@wg:protectionAction";
        if (!action.startsWith(actionPrefix)) {
            return action;
        }
        return "@wg:protectionDenied" + action.substring(actionPrefix.length());
    }

    /**
     * Return whether the given cause is whitelist (should be ignored).
     *
     * @param cause the cause
     * @param world the world
     * @param pvp whether the event in question is PvP combat
     * @return true if whitelisted
     */
    private boolean isWhitelisted(Cause cause, World world, boolean pvp) {
        Object rootCause = cause.getRootCause();

        if (rootCause instanceof Player player) {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            return !pvp && WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, localPlayer.getWorld());
        } else {
            return false;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceBlock(final PlaceBlockEvent event) {
        if (event.getResult() == Result.ALLOW) return; // Don't care about events that have been pre-allowed
        if (!isRegionSupportEnabled(event.getWorld())) return; // Region support disabled
        if (isWhitelisted(event.getCause(), event.getWorld(), false)) return; // Whitelisted cause
        if (isPistonCause(event.getCause())) return; // Controlled exclusively by the pistons flag

        final Material type = event.getEffectiveMaterial();
        final RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        final RegionAssociable associable = createRegionAssociable(event.getCause());

        // Don't check liquid flow unless it's enabled
        if (event.getCause().getRootCause() instanceof Block
                && Materials.isLiquid(type)
                && !getWorldConfig(event.getWorld()).checkLiquidFlow) {
            return;
        }

        event.filter((Predicate<Location>) target -> {
            if (target == null) return true;
            boolean canPlace;
            String what;

            /* Flint and steel, fire charge, etc. */
            if (Materials.isFire(type)) {
                Block block = event.getCause().getFirstBlock();
                boolean fire = block != null && Materials.isFire(block.getType());
                boolean lava = block != null && Materials.isLava(block.getType());
                List<StateFlag> flags = new ArrayList<>();
                flags.add(Flags.BLOCK_PLACE);
                flags.add(Flags.LIGHTER);
                if (fire) flags.add(Flags.FIRE_SPREAD);
                if (lava) flags.add(Flags.LAVA_FIRE);
                canPlace = query.testBuild(BukkitAdapter.adapt(target), associable,
                        combine(event, flags.toArray(StateFlag[]::new)));
                what = "@wg:protectionActionPlaceFire@";

            } else if (type == Material.FROSTED_ICE) {
                event.setSilent(true); // gets spammy
                canPlace = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.BLOCK_PLACE, Flags.FROSTED_ICE_FORM));
                what = "@wg:protectionActionUseFrostwalker@"; // hidden anyway
            /* Everything else */
            } else {
                canPlace = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.BLOCK_PLACE));
                what = "@wg:protectionActionPlaceBlock@";
            }

            if (!canPlace) {
                tellErrorMessage(event, event.getCause(), target, what);
                return false;
            }

            return true;
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreakBlock(final BreakBlockEvent event) {
        if (event.getResult() == Result.ALLOW) return; // Don't care about events that have been pre-allowed
        if (!isRegionSupportEnabled(event.getWorld())) return; // Region support disabled
        if (isWhitelisted(event.getCause(), event.getWorld(), false)) return; // Whitelisted cause
        if (isPistonCause(event.getCause())) return; // Controlled exclusively by the pistons flag

        final RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();

        if (!event.isCancelled()) {
            final RegionAssociable associable = createRegionAssociable(event.getCause());

            event.filter((Predicate<Location>) target -> {
                if (target == null) return true;
                boolean canBreak;
                String what;

                /* TNT */
                if (event.getCause().find(EntityType.TNT, EntityType.TNT_MINECART) != null) {
                    canBreak = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.BLOCK_BREAK, Flags.TNT));
                    what = "@wg:protectionActionUseDynamite@";

                /* Everything else */
                } else {
                    canBreak = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.BLOCK_BREAK));
                    what = "@wg:protectionActionBreakBlock@";
                }

                if (!canBreak) {
                    tellErrorMessage(event, event.getCause(), target, what);
                    return false;
                }

                return true;
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUseBlock(final UseBlockEvent event) {
        if (event.getResult() == Result.ALLOW) return; // Don't care about events that have been pre-allowed
        if (!isRegionSupportEnabled(event.getWorld())) return; // Region support disabled
        if (isWhitelisted(event.getCause(), event.getWorld(), false)) return; // Whitelisted cause

        final RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        final RegionAssociable associable = createRegionAssociable(event.getCause());

        event.filter((Predicate<Location>) target -> {
            if (target == null) return true;
            boolean canUse;
            String what;
            final Material type = target.getBlock().getType();

            /* Saplings, etc. */
            if (Materials.isConsideredBuildingIfUsed(type)) {
                canUse = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event));
                what = "@wg:protectionActionUse@";

            /* Inventory */
            } else if (Materials.isInventoryBlock(type)) {
                canUse = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.CHEST_ACCESS));
                what = "@wg:protectionActionOpen@";

            /* Inventory for blocks with the possibility to be only use, e.g. lectern */
            } else if (handleAsInventoryUsage(event.getOriginalEvent())) {
                canUse = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.CHEST_ACCESS));
                what = "@wg:protectionActionTake@";

            /* Anvils */
            } else if (Materials.isAnvil(type)) {
                canUse = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.USE_ANVIL));
                what = "@wg:protectionActionUse@";

            /* Beds */
            } else if (Materials.isBed(type)) {
                canUse = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.INTERACT, Flags.SLEEP));
                what = "@wg:protectionActionSleep@";

            /* Respawn Anchors */
            } else if(type == Material.RESPAWN_ANCHOR) {
                canUse = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.INTERACT, Flags.RESPAWN_ANCHORS));
                what = "@wg:protectionActionUseAnchors@";

            /* TNT */
            } else if (type == Material.TNT) {
                canUse = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.INTERACT, Flags.TNT));
                what = "@wg:protectionActionUseExplosives@";

            /* Legacy USE flag */
            } else if (Materials.isUseFlagApplicable(type)) {
                canUse = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.INTERACT, Flags.USE));
                what = "@wg:protectionActionUse@";

            /* Everything else */
            } else {
                canUse = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.INTERACT));
                what = "@wg:protectionActionUse@";
            }

            if (!canUse) {
                tellErrorMessage(event, event.getCause(), target, what);
                return false;
            }

            return true;
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawnEntity(SpawnEntityEvent event) {
        if (event.getResult() == Result.ALLOW) return; // Don't care about events that have been pre-allowed
        if (!isRegionSupportEnabled(event.getWorld())) return; // Region support disabled
        if (isWhitelisted(event.getCause(), event.getWorld(), false)) return; // Whitelisted cause

        Location target = event.getTarget();
        EntityType type = event.getEffectiveType();

        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        RegionAssociable associable = createRegionAssociable(event.getCause());

        boolean canSpawn;
        String what;

        /* Vehicles */
        if (Entities.isVehicle(type)) {
            canSpawn = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.PLACE_VEHICLE));
            what = "@wg:protectionActionPlaceVehicles@";

        /* Item pickup */
        } else if (event.getEntity() instanceof Item) {
            canSpawn = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.ITEM_DROP));
            what = "@wg:protectionActionDropItems@";

        /* XP drops */
        } else if (type == EntityType.EXPERIENCE_ORB) {
            canSpawn = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.EXP_DROPS));
            what = "@wg:protectionActionDropXp@";

        } else if (Entities.isAoECloud(type)) {
            canSpawn = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.POTION_SPLASH));
            what = "@wg:protectionActionUseLingeringPotions@";

        /* Everything else */
        } else {
            canSpawn = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event));
            what = "@wg:protectionActionPlaceThings@";
        }

        if (!canSpawn) {
            tellErrorMessage(event, event.getCause(), target, what);
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDestroyEntity(DestroyEntityEvent event) {
        if (event.getResult() == Result.ALLOW) return; // Don't care about events that have been pre-allowed
        if (!isRegionSupportEnabled(event.getWorld())) return; // Region support disabled
        if (isWhitelisted(event.getCause(), event.getWorld(), false)) return; // Whitelisted cause

        Location target = event.getTarget();
        EntityType type = event.getEntity().getType();
        RegionAssociable associable = createRegionAssociable(event.getCause());

        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        boolean canDestroy;
        String what;

        /* Vehicles */
        if (Entities.isVehicle(type)) {
            canDestroy = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.DESTROY_VEHICLE));
            what = "@wg:protectionActionBreakVehicles@";

        /* Item pickup */
        } else if (event.getEntity() instanceof Item || event.getEntity() instanceof ExperienceOrb) {
            canDestroy = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event, Flags.ITEM_PICKUP));
            what = "@wg:protectionActionPickupItems@";

        /* Everything else */
        } else {
            canDestroy = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event));
            what = "@wg:protectionActionBreakThings@";
        }

        if (!canDestroy) {
            tellErrorMessage(event, event.getCause(), target, what);
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUseEntity(UseEntityEvent event) {
        if (event.getResult() == Result.ALLOW) return; // Don't care about events that have been pre-allowed
        if (!isRegionSupportEnabled(event.getWorld())) return; // Region support disabled
        if (isWhitelisted(event.getCause(), event.getWorld(), false)) return; // Whitelisted cause

        Location target = event.getTarget();
        RegionAssociable associable = createRegionAssociable(event.getCause());
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        String deniedAction = deniedUseAction(event, target, associable, query);
        if (deniedAction != null) {
            tellErrorMessage(event, event.getCause(), target, deniedAction);
            event.setCancelled(true);
        }
    }

    private static boolean isPistonCause(Cause cause) {
        Block block = cause.getFirstBlock();
        return block != null && Materials.isPistonBlock(block.getType());
    }

    @Nullable
    private String deniedUseAction(
            UseEntityEvent event, Location target, RegionAssociable associable, RegionQuery query) {
        Entity entity = event.getEntity();
        if (Entities.isHostile(entity) || Entities.isAmbient(entity)
                || Entities.isNPC(entity) || entity instanceof Player) {
            boolean allowed = event.getRelevantFlags().isEmpty()
                    || query.queryState(BukkitAdapter.adapt(target), associable,
                            combine(event)) != State.DENY;
            return allowed ? null : "@wg:protectionActionUse@";
        }
        if (Entities.isConsideredBuildingIfUsed(entity)
                // weird case since sneak+interact is chest access and not ride
                || event.getOriginalEvent() instanceof InventoryOpenEvent) {
            return deniedBuildingUseAction(event, target, associable, query);
        }
        if (Entities.isRiddenOnUse(entity)) {
            if (!(event.getOriginalEvent() instanceof PlayerLeashEntityEvent)) return null;
            boolean allowed = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event));
            return allowed ? null : "@wg:protectionActionUse@";
        }
        boolean allowed = query.testBuild(
                BukkitAdapter.adapt(target), associable, combine(event, Flags.INTERACT));
        return allowed ? null : "@wg:protectionActionUse@";
    }

    @Nullable
    private String deniedBuildingUseAction(
            UseEntityEvent event, Location target, RegionAssociable associable, RegionQuery query) {
        Entity entity = event.getEntity();
        EntityType type = entity.getType();
        if ((type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME)
                && event.getCause().getFirstPlayer() != null
                && ((ItemFrame) entity).getItem().getType() != Material.AIR) {
            boolean allowed = query.testBuild(BukkitAdapter.adapt(target), associable,
                    combine(event, Flags.ITEM_FRAME_ROTATE));
            return allowed ? null : "@wg:protectionActionChange@";
        }
        if (type == EntityType.HOPPER_MINECART
                && getWorldConfig(event.getWorld()).allowHopperMinecartAccess
                && (event.getOriginalEvent() instanceof PlayerInteractEntityEvent
                        || event.getOriginalEvent() instanceof InventoryOpenEvent)) {
            boolean allowed = query.testBuild(BukkitAdapter.adapt(target), associable,
                    combine(event, Flags.HOPPER_MINECART_ACCESS));
            return allowed ? null : "@wg:protectionActionOpen@";
        }
        if (event.getOriginalEvent() instanceof InventoryOpenEvent
                || event.getOriginalEvent() instanceof InventoryMoveItemEvent) {
            boolean allowed = query.testBuild(BukkitAdapter.adapt(target), associable,
                    combine(event, Flags.CHEST_ACCESS));
            return allowed ? null : "@wg:protectionActionOpen@";
        }
        boolean allowed = query.testBuild(BukkitAdapter.adapt(target), associable, combine(event));
        return allowed ? null : "@wg:protectionActionChange@";
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageEntity(DamageEntityEvent event) {
        if (event.getResult() == Result.ALLOW) return; // Don't care about events that have been pre-allowed
        if (!isRegionSupportEnabled(event.getWorld())) return; // Region support disabled
        com.sk89q.worldedit.util.Location target = BukkitAdapter.adapt(event.getTarget());
        RegionAssociable associable = createRegionAssociable(event.getCause());
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        Player playerAttacker = event.getCause().getFirstPlayer();
        boolean pvp = event.getEntity() instanceof Player && playerAttacker != null && !playerAttacker.equals(event.getEntity());
        if (isWhitelisted(event.getCause(), event.getWorld(), pvp)) return;

        String deniedAction = deniedDamageAction(
                event, target, associable, query, playerAttacker, pvp);
        if (deniedAction != null) {
            tellErrorMessage(event, event.getCause(), event.getTarget(), deniedAction);
            event.setCancelled(true);
        }
    }

    @Nullable
    private String deniedDamageAction(
            DamageEntityEvent event,
            com.sk89q.worldedit.util.Location target,
            RegionAssociable associable,
            RegionQuery query,
            @Nullable Player playerAttacker,
            boolean pvp) {
        if (Entities.isHostile(event.getEntity()) || Entities.isAmbient(event.getEntity())) {
            boolean allowed = event.getRelevantFlags().isEmpty()
                    || query.queryState(target, associable, combine(event)) != State.DENY;
            return allowed ? null : "@wg:protectionActionHit@";
        }
        if (Entities.isVehicle(event.getEntity().getType())) {
            boolean allowed = query.testBuild(
                    target, associable, combine(event, Flags.DESTROY_VEHICLE));
            return allowed ? null : "@wg:protectionActionChange@";
        }
        if (Entities.isConsideredBuildingIfUsed(event.getEntity())) {
            boolean allowed = query.testBuild(target, associable, combine(event));
            return allowed ? null : "@wg:protectionActionChange@";
        }
        if (pvp) return deniedPvpAction(event, target, associable, query, playerAttacker);
        if (event.getEntity() instanceof Player) {
            boolean allowed = event.getRelevantFlags().isEmpty()
                    || query.queryState(target, associable, combine(event)) != State.DENY;
            return allowed ? null : "@wg:protectionActionDamage@";
        }
        if (Entities.isNonHostile(event.getEntity())) {
            boolean allowed = query.testBuild(
                    target, associable, combine(event, Flags.DAMAGE_ANIMALS));
            return allowed ? null : "@wg:protectionActionHarm@";
        }
        boolean allowed = query.testBuild(target, associable, combine(event, Flags.INTERACT));
        return allowed ? null : "@wg:protectionActionHit@";
    }

    @Nullable
    private String deniedPvpAction(
            DamageEntityEvent event,
            com.sk89q.worldedit.util.Location target,
            RegionAssociable associable,
            RegionQuery query,
            Player playerAttacker) {
        Player defender = (Player) event.getEntity();
        if (Entities.isNPC(defender)) return null;
        LocalPlayer localAttacker = WorldGuardPlugin.inst().wrapPlayer(playerAttacker);
        boolean allowed = query.testBuild(target, associable, combine(event, Flags.PVP))
                && query.queryState(localAttacker.getLocation(), localAttacker,
                        combine(event, Flags.PVP)) != State.DENY
                && query.queryState(target, localAttacker, combine(event, Flags.PVP)) != State.DENY;
        if (!allowed && Events.fireAndTestCancel(new DisallowedPVPEvent(
                playerAttacker, defender, event.getOriginalEvent()))) return null;
        return allowed ? null : "@wg:protectionActionPvp@";
    }

    @EventHandler
    public void onEntityMount(EntityMountEvent event) {
        Entity vehicle = event.getMount();
        if (!isRegionSupportEnabled(vehicle.getWorld())) return; // Region support disabled
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Cause cause = Cause.create(player);
        if (isWhitelisted(cause, vehicle.getWorld(), false)) {
            return;
        }
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        Location location = vehicle.getLocation();
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        if (!query.testBuild(BukkitAdapter.adapt(location), localPlayer, Flags.RIDE, Flags.INTERACT)) {
            event.setCancelled(true);
            DelegateEvent dummy = new UseEntityEvent(event, cause, vehicle);
            tellErrorMessage(dummy, cause, vehicle.getLocation(), "@wg:protectionActionRide@");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        Entity vehicle = event.getVehicle();
        if (!isRegionSupportEnabled(vehicle.getWorld())) return; // Region support disabled
        Entity exited = event.getExited();

        if (vehicle instanceof Tameable && exited instanceof Player player && !Entities.isNPC(player)) {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            if (!isWhitelisted(Cause.create(player), vehicle.getWorld(), false)) {
                RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
                Location location = vehicle.getLocation();
                if (!query.testBuild(BukkitAdapter.adapt(location), localPlayer, Flags.RIDE, Flags.INTERACT)) {
                    long now = System.currentTimeMillis();
                    Long lastTime = WGMetadata.getIfPresent(player, DISEMBARK_MESSAGE_KEY, Long.class);
                    if (lastTime == null || now - lastTime >= LAST_MESSAGE_DELAY) {
                        getPlugin().getMessages().send(player, "@wg:disembarkDenied@");
                        WGMetadata.put(player, DISEMBARK_MESSAGE_KEY, now);
                    }

                    event.setCancelled(true);
                }
            }
        }
    }

    /**
     * Combine the flags from a delegate event with an array of flags.
     *
     * <p>The delegate event's flags appear at the end.</p>
     *
     * @param event The event
     * @param flag An array of flags
     * @return An array of flags
     */
    private static StateFlag[] combine(DelegateEvent event, StateFlag... flag) {
        List<StateFlag> extra = event.getRelevantFlags();
        StateFlag[] flags = Arrays.copyOf(flag, flag.length + extra.size());
        for (int i = 0; i < extra.size(); i++) {
            flags[flag.length + i] = extra.get(i);
        }
        return flags;
    }

    /**
     * Check if that event should be handled as inventory usage, e.g. if a player takes a book from a lectern
     *
     * @param event the event to handle
     * @return whether it should be handled as inventory usage
     */
    private static boolean handleAsInventoryUsage(Event event) {
        return event instanceof PlayerTakeLecternBookEvent;
    }

}
