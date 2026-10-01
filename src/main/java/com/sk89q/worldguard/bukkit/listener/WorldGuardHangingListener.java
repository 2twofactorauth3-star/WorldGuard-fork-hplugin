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
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.cause.Cause;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingBreakEvent.RemoveCause;
import org.bukkit.projectiles.ProjectileSource;

import javax.annotation.Nullable;

/**
 * Listener for painting related events.
 */
public class WorldGuardHangingListener extends AbstractListener {

    public WorldGuardHangingListener(WorldGuardPlugin plugin) {
        super(plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Hanging hanging = event.getEntity();
        WorldConfiguration wcfg = getWorldConfig(hanging.getWorld());

        boolean blocked = event instanceof HangingBreakByEntityEvent entityEvent
                ? isEntityBreakBlocked(hanging, entityEvent.getRemover(), wcfg)
                : isExplosionBreakBlocked(hanging, event.getCause(), wcfg);
        if (blocked) {
            event.setCancelled(true);
        }
    }

    private boolean isEntityBreakBlocked(
            Hanging hanging, @Nullable Entity eventRemover, WorldConfiguration wcfg) {
        Entity remover = resolveRemover(eventRemover);
        if (remover instanceof Player) {
            return false;
        }
        if (remover instanceof Creeper && isCreeperBreakBlocked(hanging, remover, wcfg)) {
            return true;
        }
        return isProtectedHangingBreakBlocked(hanging, remover, wcfg);
    }

    @Nullable
    private Entity resolveRemover(@Nullable Entity remover) {
        if (!(remover instanceof Projectile projectile)) {
            return remover;
        }
        ProjectileSource shooter = projectile.getShooter();
        return shooter instanceof LivingEntity livingEntity ? livingEntity : null;
    }

    private boolean isCreeperBreakBlocked(
            Hanging hanging, Entity remover, WorldConfiguration wcfg) {
        Cause cause = Cause.create(remover);
        return blocksMechanic(wcfg.blockCreeperBlockDamage, hanging.getLocation(), cause)
                || blocksMechanic(wcfg.blockCreeperExplosions, hanging.getLocation(), cause)
                || regionsDeny(hanging, wcfg, Flags.CREEPER_EXPLOSION);
    }

    private boolean isProtectedHangingBreakBlocked(
            Hanging hanging, @Nullable Entity remover, WorldConfiguration wcfg) {
        Cause cause = Cause.create(remover);
        if (hanging instanceof Painting) {
            return blocksMechanic(wcfg.blockEntityPaintingDestroy, hanging.getLocation(), cause)
                    || regionsDeny(hanging, wcfg, Flags.ENTITY_PAINTING_DESTROY);
        }
        if (hanging instanceof ItemFrame) {
            return blocksMechanic(wcfg.blockEntityItemFrameDestroy, hanging.getLocation(), cause)
                    || regionsDeny(hanging, wcfg, Flags.ENTITY_ITEM_FRAME_DESTROY);
        }
        return false;
    }

    private boolean regionsDeny(Hanging hanging, WorldConfiguration wcfg, StateFlag flag) {
        return wcfg.useRegions && !StateFlag.test(WorldGuard.getInstance().getPlatform()
                .getRegionContainer().createQuery().queryState(
                        BukkitAdapter.adapt(hanging.getLocation()),
                        (RegionAssociable) null,
                        flag));
    }

    private boolean isExplosionBreakBlocked(
            Hanging hanging, RemoveCause cause, WorldConfiguration wcfg) {
        if (cause != RemoveCause.EXPLOSION) {
            return false;
        }
        if (hanging instanceof Painting) {
            return blocksMechanic(wcfg.blockEntityPaintingDestroy, hanging.getLocation());
        }
        return hanging instanceof ItemFrame
                && blocksMechanic(wcfg.blockEntityItemFrameDestroy, hanging.getLocation());
    }

}
