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
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.gamemode.GameMode;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitMessages;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.event.player.ProcessPlayerEvent;
import com.sk89q.worldguard.bukkit.util.Events;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.session.handler.GameModeFlag;
import com.sk89q.worldguard.util.command.CommandFilter;
import org.enginehub.squirrelid.Profile;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Handles all events thrown in relation to a player.
 */
public class WorldGuardPlayerListener extends AbstractListener {

    public WorldGuardPlayerListener(WorldGuardPlugin plugin) {
        super(plugin);
    }


    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        WorldConfiguration wcfg = getWorldConfig(player.getWorld());
        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().getIfPresent(localPlayer);
        if (session != null) {
            GameModeFlag handler = session.getHandler(GameModeFlag.class);
            if (handler != null && wcfg.useRegions && !WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer,
                    localPlayer.getWorld())) {
                GameMode expected = handler.getSetGameMode();
                if (handler.getOriginalGameMode() != null && expected != null && expected != BukkitAdapter.adapt(event.getNewGameMode())) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();

        Events.fire(new ProcessPlayerEvent(player));
        WorldGuard.getInstance().getExecutorService().submit(() ->
            WorldGuard.getInstance().getProfileCache().put(new Profile(player.getUniqueId(), player.getName())));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            handleBlockRightClick(event);
        }
    }

    /**
     * Called when a player right clicks a block.
     *
     * @param event Thrown event
     */
    private void handleBlockRightClick(PlayerInteractEvent event) {
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }

        Block block = event.getClickedBlock();
        World world = block.getWorld();
        Player player = event.getPlayer();
        @Nullable ItemStack item = event.getItem();

        WorldConfiguration wcfg = getWorldConfig(world);

        if (wcfg.useRegions) {
            LocalPlayer localPlayer = getPlugin().wrapPlayer(player);

            if (item != null && item.getType().getKey().toString().equals(wcfg.regionWand) && getPlugin().hasPermission(player, "worldguard.region.wand")) {
                ApplicableRegionSet set = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                        .getApplicableRegions(BukkitAdapter.adapt(block.getLocation()), RegionQuery.QueryOption.SORT);
                if (set.size() > 0) {
                    getPlugin().getMessages().send(player,
                            set.testState(localPlayer, Flags.BUILD)
                                    ? "@wg:canBuildAllowed@" : "@wg:canBuildDenied@");

                    StringBuilder str = new StringBuilder();
                    for (Iterator<ProtectedRegion> it = set.iterator(); it.hasNext();) {
                        str.append(it.next().getId());
                        if (it.hasNext()) {
                            str.append(", ");
                        }
                    }

                    localPlayer.print(TextComponent.of(BukkitMessages.template(
                            "applicableRegions", "regions", str)));
                } else {
                    localPlayer.print(TextComponent.of("@wg:noRegionsHere@"));
                }

                event.setUseItemInHand(Event.Result.DENY);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (com.sk89q.worldguard.bukkit.util.Entities.isNPC(player)) return;
        WorldConfiguration wcfg = getWorldConfig(player.getWorld());

        if (wcfg.useRegions) {
            LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
            ApplicableRegionSet set =
                    WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(localPlayer.getLocation());

            com.sk89q.worldedit.util.Location spawn = set.queryValue(localPlayer, Flags.SPAWN_LOC);

            if (spawn != null) {
                event.setRespawnLocation(BukkitAdapter.adapt(spawn));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) {
            // The target location for PlayerTeleportEvents can be null.
            // Those events will be ignored by the server, so we can ignore them too.
            return;
        }
        Player player = event.getPlayer();
        if (com.sk89q.worldguard.bukkit.util.Entities.isNPC(player)) return;
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        WorldConfiguration wcfg = getWorldConfig(player.getWorld());
        if (!wcfg.useRegions) return;

        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        ApplicableRegionSet destination = query.getApplicableRegions(BukkitAdapter.adapt(event.getTo()));
        ApplicableRegionSet source = query.getApplicableRegions(BukkitAdapter.adapt(event.getFrom()));
        if (isSpecialTeleportDenied(event, localPlayer, source, destination)
                || WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer)
                .testMoveTo(localPlayer, BukkitAdapter.adapt(event.getTo()), MoveType.TELEPORT) != null) {
            event.setCancelled(true);
        }
    }

    private boolean isSpecialTeleportDenied(
            PlayerTeleportEvent event,
            LocalPlayer player,
            ApplicableRegionSet source,
            ApplicableRegionSet destination) {
        StateFlag flag = switch (event.getCause()) {
            case ENDER_PEARL -> Flags.ENDERPEARL;
            case CHORUS_FRUIT -> Flags.CHORUS_TELEPORT;
            default -> null;
        };
        if (flag == null || WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(player, player.getWorld())) {
            return false;
        }
        if (!source.testState(player, flag)) {
            sendTeleportDenyMessage(event.getPlayer(), source, player, Flags.EXIT_DENY_MESSAGE);
            return true;
        }
        if (!destination.testState(player, flag)) {
            sendTeleportDenyMessage(event.getPlayer(), destination, player, Flags.ENTRY_DENY_MESSAGE);
            return true;
        }
        return false;
    }

    private void sendTeleportDenyMessage(
            Player player,
            ApplicableRegionSet regions,
            LocalPlayer localPlayer,
            com.sk89q.worldguard.protection.flags.StringFlag messageFlag) {
        String message = regions.queryValue(localPlayer, messageFlag);
        if (message != null && !message.isEmpty()) {
            getPlugin().getMessages().send(player, message);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        WorldConfiguration wcfg = getWorldConfig(player.getWorld());

        if (wcfg.useRegions && !WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, localPlayer.getWorld())) {
            ApplicableRegionSet set =
                    WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(localPlayer.getLocation());

            Set<String> allowedCommands = set.queryValue(localPlayer, Flags.ALLOWED_CMDS);
            Set<String> blockedCommands = set.queryValue(localPlayer, Flags.BLOCKED_CMDS);
            CommandFilter test = new CommandFilter(allowedCommands, blockedCommands);

            if (!test.apply(event.getMessage())) {
                String message = findBlockedCommandMessage(set, localPlayer, event.getMessage());
                if (message == null) {
                    message = set.queryValue(localPlayer, Flags.DENY_MESSAGE);
                }
                RegionProtectionListener.formatAndSendDenyMessage(BukkitMessages.template(
                        "protectionActionUseCommand", "command", event.getMessage()), localPlayer, message);
                event.setCancelled(true);
                return;
            }
        }

    }

    private static String findBlockedCommandMessage(
            ApplicableRegionSet regions, LocalPlayer player, String command) {
        String rule = command.toLowerCase(Locale.ROOT)
                .replaceAll("^/([^ :]*:)?", "/")
                .trim()
                .replaceAll("\\s+", " ");
        while (!rule.isEmpty()) {
            String message = regions.queryMapValue(player, Flags.BLOCKED_CMDS_MESSAGES, rule);
            if (message != null) {
                return message;
            }
            int separator = rule.lastIndexOf(' ');
            if (separator < 0) {
                return null;
            }
            rule = rule.substring(0, separator);
        }
        return null;
    }
}
