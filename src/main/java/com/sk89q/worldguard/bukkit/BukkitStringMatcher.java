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

import com.sk89q.worldguard.commands.framework.CommandException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.internal.platform.StringMatcher;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BukkitStringMatcher implements StringMatcher {

    @Override
    public World matchWorld(Actor sender, String filter) throws CommandException {
        List<? extends World> worlds = WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.GAME_HOOKS).getWorlds();

        if (filter.startsWith("#")) {
            return matchSpecialWorld(sender, filter, worlds);
        }
        return matchNamedWorld(filter, worlds);
    }

    private World matchNamedWorld(String filter, List<? extends World> worlds) throws CommandException {
        for (World world : worlds) {
            if (world.getName().equals(filter)) {
                return world;
            }
        }

        throw new CommandException("@wg:noExactWorld@");
    }

    private World matchSpecialWorld(Actor sender, String filter, List<? extends World> worlds)
            throws CommandException {
        return switch (filter.toLowerCase(Locale.ROOT)) {
            case "#main" -> worlds.get(0);
            case "#normal" -> matchEnvironment(
                    worlds, org.bukkit.World.Environment.NORMAL, "@wg:noNormalWorld@");
            case "#nether" -> matchEnvironment(
                    worlds, org.bukkit.World.Environment.NETHER, "@wg:noNetherWorld@");
            case "#end" -> matchEnvironment(
                    worlds, org.bukkit.World.Environment.THE_END, "@wg:noEndWorld@");
            case "#player" -> throw new CommandException("@wg:playerArgumentExpected@");
            default -> matchPlayerWorldOrThrow(sender, filter);
        };
    }

    private World matchEnvironment(
            List<? extends World> worlds,
            org.bukkit.World.Environment environment,
            String missingMessage) throws CommandException {
        for (World world : worlds) {
            if (BukkitAdapter.adapt(world).getEnvironment() == environment) {
                return world;
            }
        }
        throw new CommandException(missingMessage);
    }

    private World matchPlayerWorldOrThrow(Actor sender, String filter) throws CommandException {
        if (filter.regionMatches(true, 0, "#player:", 0, 8) && filter.length() > 8) {
            return matchPlayers(sender, filter.substring(8)).iterator().next().getWorld();
        }
        throw new CommandException(BukkitMessages.template(
                "invalidIdentifier", "identifier", filter));
    }

    @Override
    public List<LocalPlayer> matchPlayerNames(String filter) {
        return matchPlayerNames(wrapOnlinePlayers(), filter);
    }

    private List<LocalPlayer> matchPlayerNames(List<LocalPlayer> wgPlayers, String filter) {

        filter = filter.toLowerCase(Locale.ROOT);

        // Allow exact name matching
        if (filter.charAt(0) == '@' && filter.length() >= 2) {
            filter = filter.substring(1);

            for (LocalPlayer player : wgPlayers) {
                if (player.getName().equalsIgnoreCase(filter)) {
                    List<LocalPlayer> list = new ArrayList<>();
                    list.add(player);
                    return list;
                }
            }

            return new ArrayList<>();
            // Allow partial name matching
        } else if (filter.charAt(0) == '*' && filter.length() >= 2) {
            filter = filter.substring(1);

            List<LocalPlayer> list = new ArrayList<>();

            for (LocalPlayer player : wgPlayers) {
                if (player.getName().toLowerCase(Locale.ROOT).contains(filter)) {
                    list.add(player);
                }
            }

            return list;

            // Start with name matching
        } else {
            List<LocalPlayer> list = new ArrayList<>();

            for (LocalPlayer player : wgPlayers) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(filter)) {
                    list.add(player);
                }
            }

            return list;
        }
    }

    @Override
    public boolean isPlayerOnline(String input) {
        Player player = Bukkit.getPlayerExact(input);
        if (player != null) {
            return true;
        }

        String value = input.startsWith("uuid:") ? input.substring("uuid:".length()) : input;
        try {
            return Bukkit.getPlayer(UUID.fromString(value)) != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override
    public Iterable<? extends LocalPlayer> matchPlayers(Actor source, String filter) throws CommandException {
        if (Bukkit.getServer().getOnlinePlayers().isEmpty()) {
            throw new CommandException("@wg:noPlayersMatched@");
        }

        List<LocalPlayer> wgPlayers = wrapOnlinePlayers();

        if (filter.equals("*")) {
            return checkPlayerMatch(wgPlayers);
        }

        // Handle special hash tag groups
        if (filter.charAt(0) == '#') {
            // Handle #world, which matches player of the same world as the
            // calling source
            if (filter.equalsIgnoreCase("#world")) {
                List<LocalPlayer> players = new ArrayList<>();
                LocalPlayer sourcePlayer = WorldGuard.getInstance().checkPlayer(source);
                World sourceWorld = sourcePlayer.getWorld();

                for (LocalPlayer player : wgPlayers) {
                    if (player.getWorld().equals(sourceWorld)) {
                        players.add(player);
                    }
                }

                return checkPlayerMatch(players);

                // Handle #near, which is for nearby players.
            } else if (filter.equalsIgnoreCase("#near")) {
                List<LocalPlayer> players = new ArrayList<>();
                LocalPlayer sourcePlayer = WorldGuard.getInstance().checkPlayer(source);
                World sourceWorld = sourcePlayer.getWorld();
                Vector3 sourceVector = sourcePlayer.getLocation().toVector();

                for (LocalPlayer player : wgPlayers) {
                    if (player.getWorld().equals(sourceWorld) && player.getLocation().toVector().distanceSq(sourceVector) < 900) {
                        players.add(player);
                    }
                }

                return checkPlayerMatch(players);

            } else {
                throw new CommandException(BukkitMessages.template(
                        "invalidGroup", "group", filter));
            }
        }

        List<LocalPlayer> players = matchPlayerNames(wgPlayers, filter);

        return checkPlayerMatch(players);
    }

    private List<LocalPlayer> wrapOnlinePlayers() {
        Collection<? extends Player> onlinePlayers = Bukkit.getServer().getOnlinePlayers();
        List<LocalPlayer> players = new ArrayList<>(onlinePlayers.size());
        WorldGuardPlugin plugin = WorldGuardPlugin.inst();
        for (Player player : onlinePlayers) {
            players.add(plugin.wrapPlayer(player));
        }
        return players;
    }

    @Override
    public Actor matchPlayerOrConsole(Actor sender, String filter) throws CommandException {
        // Let's see if console is wanted
        if (filter.equalsIgnoreCase("#console")
                || filter.equalsIgnoreCase("*console*")
                || filter.equalsIgnoreCase("!")) {
            return WorldGuardPlugin.inst().wrapCommandSender(Bukkit.getServer().getConsoleSender());
        }

        return matchSinglePlayer(sender, filter);
    }

    @Override
    public World getWorldByName(String worldName) {
        final org.bukkit.World bukkitW = Bukkit.getServer().getWorld(worldName);
        if (bukkitW == null) {
            return null;
        }
        return BukkitAdapter.adapt(bukkitW);
    }

    @Override
    public String replaceMacros(Actor sender, String message) {
        Collection<? extends Player> online = Bukkit.getServer().getOnlinePlayers();

        message = BukkitMessages.replacePlaceholder(message, "name", sender.getName());
        message = BukkitMessages.replacePlaceholder(message, "id", sender.getUniqueId());
        message = BukkitMessages.replacePlaceholder(message, "online", online.size());

        if (sender instanceof LocalPlayer player) {
            World world = (World) player.getExtent();

            message = BukkitMessages.replacePlaceholder(message, "world", world.getName());
            message = BukkitMessages.replacePlaceholder(message, "health", player.getHealth());
        }

        return message;
    }
}
