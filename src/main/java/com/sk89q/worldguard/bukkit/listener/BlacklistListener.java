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
import com.sk89q.worldguard.blacklist.Blacklist;
import com.sk89q.worldguard.blacklist.event.BlockBreakBlacklistEvent;
import com.sk89q.worldguard.blacklist.event.BlockDispenseBlacklistEvent;
import com.sk89q.worldguard.blacklist.event.BlockInteractBlacklistEvent;
import com.sk89q.worldguard.blacklist.event.BlockPlaceBlacklistEvent;
import com.sk89q.worldguard.blacklist.event.ItemAcquireBlacklistEvent;
import com.sk89q.worldguard.blacklist.event.ItemDestroyWithBlacklistEvent;
import com.sk89q.worldguard.blacklist.event.ItemDropBlacklistEvent;
import com.sk89q.worldguard.blacklist.event.ItemEquipBlacklistEvent;
import com.sk89q.worldguard.blacklist.event.ItemUseBlacklistEvent;
import com.sk89q.worldguard.blacklist.event.EventType;
import com.sk89q.worldguard.blacklist.target.Target;
import com.sk89q.worldguard.bukkit.BukkitConfigurationManager;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent;
import com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent;
import com.sk89q.worldguard.bukkit.event.block.UseBlockEvent;
import com.sk89q.worldguard.bukkit.event.entity.DestroyEntityEvent;
import com.sk89q.worldguard.bukkit.event.entity.SpawnEntityEvent;
import com.sk89q.worldguard.bukkit.event.inventory.UseItemEvent;
import com.sk89q.worldguard.bukkit.util.Materials;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import static com.sk89q.worldguard.bukkit.BukkitUtil.createTarget;

public final class BlacklistListener extends AbstractListener {

    private final BukkitConfigurationManager configurationManager;

    public BlacklistListener(WorldGuardPlugin plugin) {
        super(plugin);
        configurationManager = plugin.getConfigManager();
    }

    private Blacklist blacklist(org.bukkit.World world) {
        if (!configurationManager.hasBlacklists()) {
            return null;
        }
        return getWorldConfig(world).getBlacklist();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreakBlock(BreakBlockEvent event) {
        Blacklist blacklist = blacklist(event.getWorld());
        if (blacklist == null) return;
        Player player = event.getCause().getFirstPlayer();
        if (player == null) return;

        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        Target heldTarget = createTarget(heldItem);
        boolean checkHeld = blacklist.needsCheck(heldTarget, EventType.DESTROY_WITH);
        event.filter(block -> {
            Target blockTarget = createTarget(block.getBlock());
            if (blacklist.needsCheck(blockTarget, EventType.BREAK)
                    && !blacklist.check(new BlockBreakBlacklistEvent(
                    localPlayer, BukkitAdapter.asBlockVector(block), blockTarget), false, false)) {
                return false;
            }
            return !checkHeld || blacklist.check(new ItemDestroyWithBlacklistEvent(
                    localPlayer, BukkitAdapter.asBlockVector(block), heldTarget), false, false);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceBlock(PlaceBlockEvent event) {
        Blacklist blacklist = blacklist(event.getWorld());
        if (blacklist == null) return;
        Player player = event.getCause().getFirstPlayer();
        if (player == null) return;

        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        event.filter(block -> {
            Target target = createTarget(block.getBlock());
            return !blacklist.needsCheck(target, EventType.PLACE)
                    || blacklist.check(new BlockPlaceBlacklistEvent(
                    localPlayer, BukkitAdapter.asBlockVector(block), target), false, false);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onUseBlock(UseBlockEvent event) {
        Blacklist blacklist = blacklist(event.getWorld());
        if (blacklist == null) return;
        Player player = event.getCause().getFirstPlayer();
        if (player == null) return;

        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        event.filter(block -> {
            Target target = createTarget(block.getBlock());
            return !blacklist.needsCheck(target, EventType.INTERACT)
                    || blacklist.check(new BlockInteractBlacklistEvent(
                    localPlayer, BukkitAdapter.asBlockVector(block), target), false, false);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawnEntity(SpawnEntityEvent event) {
        Blacklist blacklist = blacklist(event.getWorld());
        if (blacklist == null) return;
        Player player = event.getCause().getFirstPlayer();
        if (player == null) return;

        Material material = Materials.getRelatedMaterial(event.getEffectiveType());
        if (material == null) return;
        Target target = createTarget(material);
        if (blacklist.needsCheck(target, EventType.USE)
                && !blacklist.check(new ItemUseBlacklistEvent(
                getPlugin().wrapPlayer(player), BukkitAdapter.asBlockVector(event.getTarget()),
                target), false, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDestroyEntity(DestroyEntityEvent event) {
        Blacklist blacklist = blacklist(event.getWorld());
        if (blacklist == null) return;
        Player player = event.getCause().getFirstPlayer();
        if (player == null) return;

        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        Entity target = event.getEntity();
        if (target instanceof Item item) {
            Target itemTarget = createTarget(item.getItemStack());
            if (blacklist.needsCheck(itemTarget, EventType.ACQUIRE)
                    && !blacklist.check(new ItemAcquireBlacklistEvent(
                    localPlayer, BukkitAdapter.asBlockVector(target.getLocation()),
                    itemTarget), false, true)) {
                event.setCancelled(true);
                return;
            }
        }

        Material material = Materials.getRelatedMaterial(target.getType());
        if (material == null) return;
        Target blockTarget = createTarget(material);
        if (blacklist.needsCheck(blockTarget, EventType.BREAK)
                && !blacklist.check(new BlockBreakBlacklistEvent(
                localPlayer, BukkitAdapter.asBlockVector(event.getTarget()),
                blockTarget), false, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUseItem(UseItemEvent event) {
        Blacklist blacklist = blacklist(event.getWorld());
        if (blacklist == null) return;
        Player player = event.getCause().getFirstPlayer();
        if (player == null) return;

        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        ItemStack item = event.getItemStack();
        Target target = createTarget(item);
        if (blacklist.needsCheck(target, EventType.USE)
                && !blacklist.check(new ItemUseBlacklistEvent(
                localPlayer, BukkitAdapter.asBlockVector(player.getLocation()),
                target), false, false)) {
            event.setCancelled(true);
            return;
        }
        if (Materials.isArmor(item.getType())
                && blacklist.needsCheck(target, EventType.EQUIP)
                && !blacklist.check(new ItemEquipBlacklistEvent(
                localPlayer, BukkitAdapter.asBlockVector(player.getLocation()),
                target), false, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Blacklist blacklist = blacklist(event.getPlayer().getWorld());
        if (blacklist == null) return;

        Item item = event.getItemDrop();
        Target target = createTarget(item.getItemStack());
        if (blacklist.needsCheck(target, EventType.DROP)
                && !blacklist.check(new ItemDropBlacklistEvent(
                getPlugin().wrapPlayer(event.getPlayer()), BukkitAdapter.asBlockVector(item.getLocation()),
                target), false, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        Blacklist blacklist = blacklist(event.getBlock().getWorld());
        if (blacklist == null) return;
        Target target = createTarget(event.getItem());
        if (blacklist.needsCheck(target, EventType.DISPENSE)
                && !blacklist.check(new BlockDispenseBlacklistEvent(
                null, BukkitAdapter.asBlockVector(event.getBlock().getLocation()),
                target), false, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity entity = event.getWhoClicked();
        if (!(entity instanceof Player player)) return;
        Inventory inventory = event.getInventory();
        ItemStack item = event.getCurrentItem();
        if (item == null || inventory.getHolder() == null) return;

        Blacklist blacklist = blacklist(player.getWorld());
        if (blacklist == null) return;
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        Target target = createTarget(item);
        if (blacklist.needsCheck(target, EventType.ACQUIRE)
                && !blacklist.check(new ItemAcquireBlacklistEvent(
                localPlayer, BukkitAdapter.asBlockVector(entity.getLocation()),
                target), false, false)) {
            event.setCancelled(true);
            if (inventory.getHolder().equals(player)) {
                event.setCurrentItem(null);
            }
        }

        ItemStack equipped = checkEquipped(event);
        if (equipped != null) {
            Target equippedTarget = createTarget(equipped);
            if (blacklist.needsCheck(equippedTarget, EventType.EQUIP)
                    && !blacklist.check(new ItemEquipBlacklistEvent(
                    localPlayer, BukkitAdapter.asBlockVector(player.getLocation()),
                    equippedTarget), false, false)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        HumanEntity entity = event.getWhoClicked();
        if (!(entity instanceof Player player)) return;
        InventoryType type = event.getInventory().getType();
        if (type != InventoryType.PLAYER && type != InventoryType.CRAFTING) return;

        boolean armorSlot = false;
        for (int slot : event.getRawSlots()) {
            if (slot >= 5 && slot <= 8) {
                armorSlot = true;
                break;
            }
        }
        if (!armorSlot) return;

        Blacklist blacklist = blacklist(player.getWorld());
        if (blacklist == null) return;
        Target target = createTarget(event.getOldCursor());
        if (blacklist.needsCheck(target, EventType.EQUIP)
                && !blacklist.check(new ItemEquipBlacklistEvent(
                getPlugin().wrapPlayer(player), BukkitAdapter.asBlockVector(player.getLocation()),
                target), false, false)) {
            event.setCancelled(true);
        }
    }

    private ItemStack checkEquipped(InventoryClickEvent event) {
        Inventory clickedInventory = event.getClickedInventory();
        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            return switch (event.getAction()) {
                case PLACE_ONE, PLACE_SOME, PLACE_ALL, SWAP_WITH_CURSOR -> event.getCursor();
                case HOTBAR_SWAP -> event.getClick() == ClickType.SWAP_OFFHAND
                        ? clickedInventory == null ? null : ((PlayerInventory) clickedInventory).getItemInOffHand()
                        : clickedInventory == null ? null : clickedInventory.getItem(event.getHotbarButton());
                default -> null;
            };
        }
        if (clickedInventory != null
                && clickedInventory.getType() == InventoryType.PLAYER
                && (event.getView().getTopInventory().getType() == InventoryType.PLAYER
                || event.getView().getTopInventory().getType() == InventoryType.CRAFTING)
                && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return event.getCurrentItem();
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        HumanEntity entity = event.getWhoClicked();
        ItemStack item = event.getCursor();
        if (item.getType() == Material.AIR || !(entity instanceof Player player)) return;

        Blacklist blacklist = blacklist(player.getWorld());
        if (blacklist == null) return;
        Target target = createTarget(item);
        if (blacklist.needsCheck(target, EventType.ACQUIRE)
                && !blacklist.check(new ItemAcquireBlacklistEvent(
                getPlugin().wrapPlayer(player), BukkitAdapter.asBlockVector(entity.getLocation()),
                target), false, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Inventory inventory = player.getInventory();
        ItemStack item = inventory.getItem(event.getNewSlot());
        if (item == null) return;

        Blacklist blacklist = blacklist(player.getWorld());
        if (blacklist == null) return;
        Target target = createTarget(item);
        if (blacklist.needsCheck(target, EventType.ACQUIRE)
                && !blacklist.check(new ItemAcquireBlacklistEvent(
                getPlugin().wrapPlayer(player), BukkitAdapter.asBlockVector(player.getLocation()),
                target), false, false)) {
            inventory.setItem(event.getNewSlot(), null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDispenseArmor(BlockDispenseArmorEvent event) {
        if (!(event.getTargetEntity() instanceof Player player)) return;
        Blacklist blacklist = blacklist(player.getWorld());
        if (blacklist == null) return;
        Target target = createTarget(event.getItem());
        if (blacklist.needsCheck(target, EventType.EQUIP)
                && !blacklist.check(new ItemEquipBlacklistEvent(
                getPlugin().wrapPlayer(player), BukkitAdapter.asBlockVector(player.getLocation()),
                target), false, true)) {
            event.setCancelled(true);
        }
    }
}
