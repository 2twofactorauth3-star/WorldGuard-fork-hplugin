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
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitWorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.cause.Cause;
import com.sk89q.worldguard.bukkit.util.Materials;
import com.sk89q.worldguard.config.ConfigurationManager;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowman;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;

/**
 * The listener for block events.
 */
public class WorldGuardBlockListener extends AbstractListener {


    /**
     * Construct the object.
     *
     * @param plugin The plugin instance
     */
    public WorldGuardBlockListener(WorldGuardPlugin plugin) {
        super(plugin);
    }

    /*
     * Called when fluids flow.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        World world = event.getBlock().getWorld();
        Block blockFrom = event.getBlock();
        Block blockTo = event.getToBlock();

        Material fromType = blockFrom.getType();
        boolean isWater = Materials.isWater(fromType);
        boolean isLava = fromType == Material.LAVA;
        boolean isAir = fromType == Material.AIR;

        WorldConfiguration wcfg = getWorldConfig(world);

        /*if (plugin.classicWater && isWater) {
        int blockBelow = blockFrom.getRelative(0, -1, 0).getTypeId();
        if (blockBelow != 0 && blockBelow != 8 && blockBelow != 9) {
        blockFrom.setTypeId(9);
        if (blockTo.getTypeId() == 0) {
        blockTo.setTypeId(9);
        }
        return;
        }
        }*/

        // Check the fluid block (from) whether it is air.
        // If so and the target block is protected, cancel the event
        if (!wcfg.preventWaterDamage.isEmpty()
                && blocksMechanic(wcfg.preventWaterDamageSetting, blockTo.getLocation(), Cause.create(blockFrom))) {
            Material targetId = blockTo.getType();

            if ((isAir || isWater) &&
                    wcfg.preventWaterDamage.contains(BukkitAdapter.asBlockType(targetId).id())) {
                event.setCancelled(true);
                return;
            }
        }

        if (!wcfg.allowedLavaSpreadOver.isEmpty() && isLava
                && blocksMechanic(wcfg.allowedLavaSpreadOverSetting, blockTo.getLocation(), Cause.create(blockFrom))) {
            Material targetId = blockTo.getRelative(0, -1, 0).getType();

            if (!wcfg.allowedLavaSpreadOver.contains(BukkitAdapter.asBlockType(targetId).id())) {
                event.setCancelled(true);
                return;
            }
        }

        if (wcfg.highFreqFlags && (isWater || blockFrom.getBlockData() instanceof Waterlogged)
                && WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().queryState(BukkitAdapter.adapt(blockFrom.getLocation()), (RegionAssociable) null, Flags.WATER_FLOW) == StateFlag.State.DENY) {
            event.setCancelled(true);
            return;
        }

        if (wcfg.highFreqFlags && isLava
                && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().queryState(BukkitAdapter.adapt(blockFrom.getLocation()), (RegionAssociable) null, Flags.LAVA_FLOW))) {
            event.setCancelled(true);
            return;
        }
    }

    /*
     * Called when a block gets ignited.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        IgniteCause cause = event.getCause();
        Block block = event.getBlock();
        WorldConfiguration wcfg = getWorldConfig(block.getWorld());
        Cause mechanicCause = createIgniteCause(event);

        if (isConfiguredIgnitionBlocked(event, wcfg, mechanicCause)
                || event.getCause() == IgniteCause.SPREAD
                        && isAdjacentFireSpreadBlocked(block, wcfg, mechanicCause)
                || isRegionalIgnitionBlocked(event, wcfg)) {
            event.setCancelled(true);
        }
    }

    private Cause createIgniteCause(BlockIgniteEvent event) {
        if (event.getPlayer() != null) {
            return Cause.create(event.getPlayer());
        }
        if (event.getIgnitingEntity() != null) {
            return Cause.create(event.getIgnitingEntity());
        }
        if (event.getIgnitingBlock() != null) {
            return Cause.create(event.getIgnitingBlock());
        }
        return Cause.unknown();
    }

    private boolean isConfiguredIgnitionBlocked(
            BlockIgniteEvent event, WorldConfiguration wcfg, Cause mechanicCause) {
        IgniteCause cause = event.getCause();
        Location location = event.getBlock().getLocation();
        if (cause == IgniteCause.LAVA && blocksMechanic(wcfg.preventLavaFire, location, mechanicCause)) {
            return true;
        }
        if (cause == IgniteCause.SPREAD && blocksMechanic(wcfg.disableFireSpread, location, mechanicCause)) {
            return true;
        }
        return (cause == IgniteCause.FLINT_AND_STEEL || cause == IgniteCause.FIREBALL)
                && event.getPlayer() != null
                && blocksMechanic(wcfg.blockLighter, location, mechanicCause)
                && !getPlugin().hasPermission(event.getPlayer(), "worldguard.override.lighter");
    }

    private boolean isAdjacentFireSpreadBlocked(
            Block block, WorldConfiguration wcfg, Cause mechanicCause) {
        if (wcfg.disableFireSpreadBlocks.isEmpty()
                || !blocksMechanic(wcfg.disableFireSpreadBlocksSetting, block.getLocation(), mechanicCause)) {
            return false;
        }
        int[][] offsets = {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int[] offset : offsets) {
            Material type = block.getRelative(offset[0], offset[1], offset[2]).getType();
            if (wcfg.disableFireSpreadBlocks.contains(BukkitAdapter.asBlockType(type).id())) {
                return true;
            }
        }
        return false;
    }

    private boolean isRegionalIgnitionBlocked(BlockIgniteEvent event, WorldConfiguration wcfg) {
        if (!wcfg.useRegions) {
            return false;
        }
        ApplicableRegionSet set = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .createQuery().getApplicableRegions(BukkitAdapter.adapt(event.getBlock().getLocation()));
        return switch (event.getCause()) {
            case SPREAD -> wcfg.highFreqFlags && !set.testState(null, Flags.FIRE_SPREAD);
            case LAVA -> wcfg.highFreqFlags && !set.testState(null, Flags.LAVA_FIRE);
            case FIREBALL -> event.getPlayer() == null && !set.testState(null, Flags.GHAST_FIREBALL);
            case LIGHTNING -> !set.testState(null, Flags.LIGHTNING);
            default -> false;
        };
    }

    /*
     * Called when a block is destroyed from burning.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        BukkitWorldConfiguration wcfg = getWorldConfig(event.getBlock().getWorld());

        if (blocksMechanic(wcfg.disableFireSpread, event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }

        if (!wcfg.disableFireSpreadBlocks.isEmpty()
                && blocksMechanic(wcfg.disableFireSpreadBlocksSetting, event.getBlock().getLocation())) {
            Block block = event.getBlock();

            if (wcfg.disableFireSpreadBlocks.contains(BukkitAdapter.asBlockType(block.getType()).id())) {
                event.setCancelled(true);
                checkAndDestroyFireAround(block.getWorld(), block.getX(), block.getY(), block.getZ());
                return;
            }
        }

        if (wcfg.useRegions) {
            Block block = event.getBlock();
            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();
            ApplicableRegionSet set =
                    WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(BukkitAdapter.adapt(block.getLocation()));

            if (!set.testState(null, Flags.FIRE_SPREAD)) {
                checkAndDestroyFireAround(block.getWorld(), x, y, z);
                event.setCancelled(true);
            }

        }
    }

    private void checkAndDestroyFireAround(World world, int x, int y, int z) {
        checkAndDestroyFire(world, x, y, z + 1);
        checkAndDestroyFire(world, x, y, z - 1);
        checkAndDestroyFire(world, x, y + 1, z);
        checkAndDestroyFire(world, x, y - 1, z);
        checkAndDestroyFire(world, x + 1, y, z);
        checkAndDestroyFire(world, x - 1, y, z);
    }

    private void checkAndDestroyFire(World world, int x, int y, int z) {
        if (Materials.isFire(world.getBlockAt(x, y, z).getType())) {
            world.getBlockAt(x, y, z).setType(Material.AIR);
        }
    }

    /*
     * Called when block physics occurs.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        WorldConfiguration wcfg = getWorldConfig(event.getBlock().getWorld());
        final Material id = event.getBlock().getType();

        if (id == Material.GRAVEL
                && blocksMechanic(wcfg.noPhysicsGravel, event.getBlock().getLocation(),
                        Cause.create(event.getSourceBlock()))) {
            event.setCancelled(true);
            return;
        }

        if ((id == Material.SAND || id == Material.RED_SAND)
                && blocksMechanic(wcfg.noPhysicsSand, event.getBlock().getLocation(),
                        Cause.create(event.getSourceBlock()))) {
            event.setCancelled(true);
            return;
        }

        if (id == Material.NETHER_PORTAL
                && blocksMechanic(wcfg.allowPortalAnywhere, event.getBlock().getLocation(),
                        Cause.create(event.getSourceBlock()))) {
            event.setCancelled(true);
            return;
        }

    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        WorldConfiguration wcfg = getWorldConfig(event.getBlock().getWorld());

        if (wcfg.useRegions) {
            if (!StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().queryState(BukkitAdapter.adapt(event.getBlock().getLocation()), (RegionAssociable) null, Flags.LEAF_DECAY))) {
                event.setCancelled(true);
            }
        }
    }

    /*
     * Called when a block is formed based on world conditions.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        WorldConfiguration wcfg = getWorldConfig(event.getBlock().getWorld());

        Material type = event.getNewState().getType();

        if (event instanceof EntityBlockFormEvent) {
            if (((EntityBlockFormEvent) event).getEntity() instanceof Snowman) {
                if (blocksMechanic(wcfg.disableSnowmanTrails, event.getBlock().getLocation(),
                        Cause.create(((EntityBlockFormEvent) event).getEntity()))) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }

        if (type == Material.ICE) {
            if (wcfg.useRegions && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(BukkitAdapter.adapt(event.getBlock().getLocation()), (RegionAssociable) null, Flags.ICE_FORM))) {
                event.setCancelled(true);
                return;
            }
        }

        if (type == Material.SNOW) {
            if (wcfg.useRegions && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(BukkitAdapter.adapt(event.getBlock().getLocation()), (RegionAssociable) null, Flags.SNOW_FALL))) {
                event.setCancelled(true);
                return;
            }
        }

        if (Materials.isUnwaxedCopper(event.getBlock().getType())) {
            if (wcfg.useRegions && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(BukkitAdapter.adapt(event.getBlock().getLocation()), (RegionAssociable) null, Flags.COPPER_FADE))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /*
     * Called when a block spreads based on world conditions.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        WorldConfiguration wcfg = getWorldConfig(event.getBlock().getWorld());
        Material newType = event.getNewState().getType(); // craftbukkit randomly gives AIR as event.getSource even if that block is not air
        StateFlag spreadFlag = spreadFlag(newType);
        if (wcfg.useRegions && spreadFlag != null && !StateFlag.test(
                WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                        .queryState(BukkitAdapter.adapt(event.getBlock().getLocation()),
                                (RegionAssociable) null, spreadFlag))) {
            event.setCancelled(true);
            return;
        }

        handleGrow(event, event.getBlock().getLocation(), newType);
    }

    private StateFlag spreadFlag(Material type) {
        if (Materials.isMushroom(type)) return Flags.MUSHROOMS;
        if (type == Material.GRASS_BLOCK) return Flags.GRASS_SPREAD;
        if (type == Material.MYCELIUM) return Flags.MYCELIUM_SPREAD;
        if (Materials.isVine(type)) return Flags.VINE_GROWTH;
        if (Materials.isAmethystGrowth(type) || type == Material.POINTED_DRIPSTONE) return Flags.ROCK_GROWTH;
        if (Materials.isSculkGrowth(type)) return Flags.SCULK_GROWTH;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        Location loc = event.getBlock().getLocation();
        final Material type = event.getNewState().getType();

        handleGrow(event, loc, type);
    }

    private void handleGrow(Cancellable event, Location loc, Material type) {
        WorldConfiguration wcfg = getWorldConfig(loc.getWorld());
        if (Materials.isCrop(type)) {

            if (wcfg.useRegions && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .queryState(BukkitAdapter.adapt(loc), (RegionAssociable) null, Flags.CROP_GROWTH))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /*
     * Called when a block fades.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {

        WorldConfiguration wcfg = getWorldConfig(event.getBlock().getWorld());

        if (event.getBlock().getType() == Material.ICE) {

            if (wcfg.useRegions && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(BukkitAdapter.adapt(event.getBlock().getLocation()), (RegionAssociable) null, Flags.ICE_MELT))) {
                event.setCancelled(true);
                return;
            }
        } else if (event.getBlock().getType() == Material.FROSTED_ICE) {
            if (wcfg.useRegions && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(BukkitAdapter.adapt(event.getBlock().getLocation()), (RegionAssociable) null, Flags.FROSTED_ICE_MELT))) {
                event.setCancelled(true);
                return;
            }
        } else if (event.getBlock().getType() == Material.SNOW) {

            if (wcfg.useRegions && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(BukkitAdapter.adapt(event.getBlock().getLocation()), (RegionAssociable) null, Flags.SNOW_MELT))) {
                event.setCancelled(true);
                return;
            }
        } else if (event.getBlock().getType() == Material.FARMLAND) {
            if (wcfg.useRegions && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(BukkitAdapter.adapt(event.getBlock().getLocation()), (RegionAssociable) null, Flags.SOIL_DRY))) {
                event.setCancelled(true);
                return;
            }
        } else if (Materials.isCoral(event.getBlock().getType())) {
            if (wcfg.useRegions && !StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(BukkitAdapter.adapt(event.getBlock().getLocation()), (RegionAssociable) null, Flags.CORAL_FADE))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        WorldConfiguration wcfg = getWorldConfig(event.getBlock().getWorld());
        Cause cause = Cause.create(event.getBlock());
        boolean blocked = blocksMechanic(wcfg.blockOtherExplosions, event.getBlock().getLocation(), cause);
        if (!blocked) {
            blocked = event.blockList().stream()
                    .anyMatch(block -> blocksMechanic(wcfg.blockOtherExplosions, block.getLocation(), cause));
        }
        if (blocked) {
            event.setCancelled(true);
        }
    }

    /**
     * Called when the moisture level of a block changes
     */
    @EventHandler(ignoreCancelled = true)
    public void onMoistureChange(MoistureChangeEvent event) {
        WorldConfiguration wcfg = getWorldConfig(event.getBlock().getWorld());

        if (wcfg.useRegions) {
            if (!StateFlag.test(WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .queryState(BukkitAdapter.adapt(event.getBlock().getLocation()), (RegionAssociable) null, Flags.MOISTURE_CHANGE))) {
                event.setCancelled(true);
            }
        }
    }

}
