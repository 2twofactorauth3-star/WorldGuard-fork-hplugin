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

package com.sk89q.worldguard.session.handler;

import com.sk89q.worldedit.world.gamemode.GameModes;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.session.Session;

public class HealFlag extends Handler {

    public static final Factory FACTORY = new Factory();
    public static class Factory extends Handler.Factory<HealFlag> {
        @Override
        public HealFlag create(Session session) {
            return new HealFlag(session);
        }
    }

    private long lastHeal = 0;

    public HealFlag(Session session) {
        super(session);
    }

    @Override
    public void tick(LocalPlayer player, ApplicableRegionSet set) {
        double health = player.getHealth();
        if (health <= 0) {
            return;
        }
        Integer healAmount = set.queryValue(player, Flags.HEAL_AMOUNT);
        if (healAmount == null || healAmount == 0) {
            return;
        }
        Integer healDelay = set.queryValue(player, Flags.HEAL_DELAY);
        if (healDelay == null || healDelay < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (healDelay > 0 && now - lastHeal <= healDelay * 1000L) {
            return;
        }
        if (applyHealing(player, set, health, healAmount, healDelay)) {
            lastHeal = now;
        }
    }

    private boolean applyHealing(LocalPlayer player, ApplicableRegionSet set, double health, int healAmount, int healDelay) {
        if (healAmount < 0
                && (getSession().isInvincible(player)
                || (player.getGameMode() != GameModes.SURVIVAL && player.getGameMode() != GameModes.ADVENTURE))) {
            // don't damage invincible players
            return false;
        }
        Double minHealth = set.queryValue(player, Flags.MIN_HEAL);
        Double maxHealth = set.queryValue(player, Flags.MAX_HEAL);
        double maximum = player.getMaxHealth();
        if (minHealth == null) {
            minHealth = 0.0;
        }
        if (maxHealth == null) {
            maxHealth = maximum;
        }

        minHealth = Math.min(maximum, minHealth);
        maxHealth = Math.min(maximum, maxHealth);

        if (health >= maxHealth && healAmount > 0) {
            return false;
        }

        if (healDelay <= 0) {
            player.setHealth(healAmount > 0 ? maxHealth : minHealth);
        } else {
            player.setHealth(Math.clamp(health + healAmount, minHealth, maxHealth));
        }
        return true;
    }

}
