/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.sk89q.worldguard.bukkit;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.blacklist.target.BlockTarget;
import com.sk89q.worldguard.blacklist.target.ItemTarget;
import com.sk89q.worldguard.blacklist.target.Target;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

public final class BukkitUtil {

    private static final Target[] TARGETS = new Target[Material.values().length];

    private BukkitUtil() {
    }

    public static Target createTarget(Block block) {
        return createTarget(block.getType());
    }

    public static Target createTarget(ItemStack item) {
        return createTarget(item.getType());
    }

    public static Target createTarget(Material material) {
        int ordinal = material.ordinal();
        Target target = TARGETS[ordinal];
        if (target != null) {
            return target;
        }
        synchronized (TARGETS) {
            target = TARGETS[ordinal];
            if (target == null) {
                target = material.isBlock()
                        ? new BlockTarget(BukkitAdapter.asBlockType(material))
                        : new ItemTarget(BukkitAdapter.asItemType(material));
                TARGETS[ordinal] = target;
            }
            return target;
        }
    }
}
