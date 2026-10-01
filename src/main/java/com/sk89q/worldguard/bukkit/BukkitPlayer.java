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

package com.sk89q.worldguard.bukkit;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.world.weather.WeatherType;
import com.sk89q.worldedit.world.weather.WeatherTypes;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.util.MessagingUtil;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.time.Duration;
import net.kyori.adventure.title.Title;

public class BukkitPlayer extends com.sk89q.worldedit.bukkit.BukkitPlayer implements LocalPlayer {
    private volatile Session worldGuardSession;

    protected final WorldGuardPlugin plugin;
    private final boolean silenced;
    private String name;

    public BukkitPlayer(WorldGuardPlugin plugin, Player player) {
        this(plugin, player, false);
    }

    BukkitPlayer(WorldGuardPlugin plugin, Player player, boolean silenced) {
        super(player);
        this.plugin = plugin;
        this.silenced = silenced;
    }

    @Override
    public String getName() {
        if (this.name == null) {
            // getName() takes longer than before in newer versions of Minecraft
            this.name = getPlayer().getName();
        }
        return name;
    }

    @Override
    public boolean hasGroup(String group) {
        return plugin.inGroup(getPlayer(), group);
    }

    @Override
    public void kick(String msg) {
        if (!silenced) {
            getPlayer().kick(plugin.getMessages().component(msg));
        }
    }

    @Override
    public void ban(String msg) {
        if (!silenced) {
            getPlayer().ban(null, (java.time.Instant) null, null, true);
            getPlayer().kick(plugin.getMessages().component(msg));
        }
    }

    @Override
    public double getHealth() {
        return getPlayer().getHealth();
    }

    @Override
    public void setHealth(double health) {
        getPlayer().setHealth(health);
    }

    @Override
    public double getMaxHealth() {
        return getPlayer().getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
    }

    @Override
    public double getFoodLevel() {
        return getPlayer().getFoodLevel();
    }

    @Override
    public void setFoodLevel(double foodLevel) {
        getPlayer().setFoodLevel((int) foodLevel);
    }

    @Override
    public double getSaturation() {
        return getPlayer().getSaturation();
    }

    @Override
    public void setSaturation(double saturation) {
        getPlayer().setSaturation((float) saturation);
    }

    @Override
    public float getExhaustion() {
        return getPlayer().getExhaustion();
    }

    @Override
    public void setExhaustion(float exhaustion) {
        getPlayer().setExhaustion(exhaustion);
    }

    @Override
    public WeatherType getPlayerWeather() {
        org.bukkit.WeatherType playerWeather = getPlayer().getPlayerWeather();
        return playerWeather == null ? null : playerWeather == org.bukkit.WeatherType.CLEAR ? WeatherTypes.CLEAR : WeatherTypes.RAIN;
    }

    @Override
    public void setPlayerWeather(WeatherType weather) {
        getPlayer().setPlayerWeather(weather == WeatherTypes.CLEAR ? org.bukkit.WeatherType.CLEAR : org.bukkit.WeatherType.DOWNFALL);
    }

    @Override
    public void resetPlayerWeather() {
        getPlayer().resetPlayerWeather();
    }

    @Override
    public boolean isPlayerTimeRelative() {
        return getPlayer().isPlayerTimeRelative();
    }

    @Override
    public long getPlayerTimeOffset() {
        return getPlayer().getPlayerTimeOffset();
    }

    @Override
    public void setPlayerTime(long time, boolean relative) {
        getPlayer().setPlayerTime(time, relative);
    }

    @Override
    public void resetPlayerTime() {
        getPlayer().resetPlayerTime();
    }

    @Override
    public int getFireTicks() {
        return getPlayer().getFireTicks();
    }

    @Override
    public void setFireTicks(int fireTicks) {
        getPlayer().setFireTicks(fireTicks);
    }

    @Override
    public void setCompassTarget(Location location) {
        getPlayer().setCompassTarget(BukkitAdapter.adapt(location));
    }

    @Override
    public void sendTitle(String title, String subtitle) {
        net.kyori.adventure.text.Component titleComponent = plugin.getMessages().component(title);
        net.kyori.adventure.text.Component subtitleComponent = plugin.getMessages().component(subtitle);
        if (WorldGuard.getInstance().getPlatform().getGlobalStateManager().get(getWorld()).forceDefaultTitleTimes) {
            getPlayer().showTitle(Title.title(titleComponent, subtitleComponent,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000))));
        } else {
            getPlayer().showTitle(Title.title(titleComponent, subtitleComponent));
        }
    }

    @Override
    public void resetFallDistance() {
        getPlayer().setFallDistance(0);
    }

    @Override
    public void teleport(Location location, String successMessage, String failMessage) {
        getPlayer().teleportAsync(BukkitAdapter.adapt(location))
                .thenApply(success -> {
                    if (success) {
                        // The success message can be cleared via flag
                        if (!successMessage.isEmpty()) {
                            MessagingUtil.sendStringToChat(this, successMessage);
                        }
                    } else {
                        printError(failMessage);
                    }
                    return success;
                });
    }

    @Override
    public String[] getGroups() {
        return plugin.getGroups(getPlayer());
    }

    @Override
    public void printRaw(String msg) {
        if (!silenced) {
            plugin.getMessages().send(getPlayer(), msg);
        }
    }

    @Override
    public void print(String msg) {
        if (!silenced) {
            plugin.getMessages().send(getPlayer(), msg);
        }
    }

    @Override
    public void printDebug(String msg) {
        if (!silenced) {
            plugin.getMessages().send(getPlayer(), msg);
        }
    }

    @Override
    public void printError(String msg) {
        if (!silenced) {
            plugin.getMessages().send(getPlayer(), msg);
        }
    }

    @Override
    public void print(Component component) {
        if (!silenced) {
            plugin.getMessages().send(getPlayer(), component);
        }
    }

    @Override
    public boolean hasPermission(String perm) {
        return plugin.hasPermission(getPlayer(), perm);
    }

    public Session getWorldGuardSession() {
        return worldGuardSession;
    }

    public void setWorldGuardSession(Session session) {
        worldGuardSession = session;
    }
}
