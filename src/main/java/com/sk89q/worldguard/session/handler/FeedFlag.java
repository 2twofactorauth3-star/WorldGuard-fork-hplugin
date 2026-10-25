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

public class FeedFlag extends Handler {

    public static final Factory FACTORY = new Factory();
    public static class Factory extends Handler.Factory<FeedFlag> {
        @Override
        public FeedFlag create(Session session) {
            return new FeedFlag(session);
        }
    }

    private long lastFeed = 0;

    public FeedFlag(Session session) {
        super(session);
    }

    @Override
    public void tick(LocalPlayer player, ApplicableRegionSet set) {
        Integer feedAmount = set.queryValue(player, Flags.FEED_AMOUNT);
        if (feedAmount == null || feedAmount == 0) {
            return;
        }
        Integer feedDelay = set.queryValue(player, Flags.FEED_DELAY);
        if (feedDelay == null || feedDelay < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (feedDelay > 0 && now - lastFeed <= feedDelay * 1000L) {
            return;
        }
        Integer minHunger = set.queryValue(player, Flags.MIN_FOOD);
        Integer maxHunger = set.queryValue(player, Flags.MAX_FOOD);
        if (feedAmount < 0
                && (getSession().isInvincible(player)
                || (player.getGameMode() != GameModes.SURVIVAL && player.getGameMode() != GameModes.ADVENTURE))) {
            // don't starve invincible players
            return;
        }
        if (minHunger == null) {
            minHunger = 0;
        }
        if (maxHunger == null) {
            maxHunger = 20;
        }

        // Apply a cap to prevent possible exceptions
        minHunger = Math.min(20, minHunger);
        maxHunger = Math.min(20, maxHunger);

        if (player.getFoodLevel() >= maxHunger && feedAmount > 0) {
            return;
        }

        if (feedDelay <= 0) {
            player.setFoodLevel(feedAmount > 0 ? maxHunger : minHunger);
        } else {
            // clamp health between minimum and maximum
            player.setFoodLevel(Math.clamp(player.getFoodLevel() + feedAmount, minHunger, maxHunger));
        }
        player.setSaturation(player.getFoodLevel());
        lastFeed = now;
    }

}
