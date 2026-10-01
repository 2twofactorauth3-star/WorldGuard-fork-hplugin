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

package com.sk89q.worldguard.commands.region;

import com.sk89q.worldguard.commands.framework.CommandContext;
import com.sk89q.worldguard.commands.framework.CommandException;
import com.sk89q.worldguard.commands.framework.CommandPermissionsException;
import com.sk89q.worldedit.command.util.AsyncCommandBuilder;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.auth.AuthorizationException;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitMessages;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.util.DomainInputResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class MemberCommands extends RegionCommandsBase {

    private final WorldGuard worldGuard;
    private final OfflinePlayerAdditionConfirmation offlineConfirmation =
            new OfflinePlayerAdditionConfirmation();

    public MemberCommands(WorldGuard worldGuard) {
        this.worldGuard = worldGuard;
    }

    public void addMember(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = checkWorld(args, sender, 'w'); // Get the world
        String id = args.getString(0);
        RegionManager manager = checkRegionManager(world);
        ProtectedRegion region = checkExistingRegion(manager, id, true);

        // Check permissions
        if (!getPermissionModel(sender).mayAddMembers(region)) {
            throw new CommandPermissionsException();
        }
        requireOfflinePlayerConfirmation(args, sender);

        // Resolve members asynchronously
        DomainInputResolver resolver = new DomainInputResolver(
                WorldGuard.getInstance().getProfileService(), args.getParsedPaddedSlice(1, 0));

        final String description = BukkitMessages.template(
                "taskAddingMembers", "region", region.getId(), "world", world.getName());
        AsyncCommandBuilder.wrap(resolver, sender)
                .registerWithSupervisor(worldGuard.getSupervisor(), description)
                .onSuccess(TextComponent.of(BukkitMessages.template(
                        "membersAdded", "region", region.getId())), region.getMembers()::addAll)
                .onFailure(TextComponent.of("@wg:membersAddFailed@"), worldGuard.getExceptionConverter())
                .buildAndExec(worldGuard.getExecutorService());
    }

    public void addOwner(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = checkWorld(args, sender, 'w'); // Get the world

        String id = args.getString(0);

        RegionManager manager = checkRegionManager(world);
        ProtectedRegion region = checkExistingRegion(manager, id, true);

        // Check permissions
        if (!getPermissionModel(sender).mayAddOwners(region)) {
            throw new CommandPermissionsException();
        }
        requireOfflinePlayerConfirmation(args, sender);

        // Resolve owners asynchronously
        DomainInputResolver resolver = new DomainInputResolver(
                WorldGuard.getInstance().getProfileService(), args.getParsedPaddedSlice(1, 0));

        final String description = BukkitMessages.template(
                "taskAddingOwners", "region", region.getId(), "world", world.getName());
        AsyncCommandBuilder.wrap(checkedAddOwners(sender, manager, region, world, resolver), sender)
                .registerWithSupervisor(worldGuard.getSupervisor(), description)
                .onSuccess(TextComponent.of(BukkitMessages.template(
                        "ownersAdded", "region", region.getId())), region.getOwners()::addAll)
                .onFailure(TextComponent.of("@wg:ownersAddFailed@"), worldGuard.getExceptionConverter())
                .buildAndExec(worldGuard.getExecutorService());
    }

    private void requireOfflinePlayerConfirmation(CommandContext args, Actor sender) throws CommandException {
        if (!worldGuard.getPlatform().getGlobalStateManager().confirmOfflinePlayerAdditions
                || !(sender instanceof LocalPlayer player)) {
            return;
        }

        List<String> offlinePlayers = new ArrayList<>();
        for (String input : args.getSlice(1)) {
            if (!input.regionMatches(true, 0, "g:", 0, 2)
                    && !worldGuard.getPlatform().getMatcher().isPlayerOnline(input)) {
                offlinePlayers.add(input);
            }
        }
        if (offlinePlayers.isEmpty()) {
            offlineConfirmation.clear(player.getUniqueId());
            return;
        }
        if (offlineConfirmation.confirm(player.getUniqueId(), args.rawCommand)) {
            return;
        }

        throw new CommandException(BukkitMessages.template(
                "offlinePlayerAdditionConfirmation",
                "players", String.join(", ", offlinePlayers),
                "seconds", OfflinePlayerAdditionConfirmation.TIMEOUT_SECONDS,
                "command", args.rawCommand));
    }

    private static Callable<DefaultDomain> checkedAddOwners(Actor sender, RegionManager manager, ProtectedRegion region,
                                                            World world, DomainInputResolver resolver) {
        return () -> {
            DefaultDomain owners = resolver.call();
            // Ownership limits apply to the invoking player; resolved additions are not re-evaluated here.
            if (sender instanceof LocalPlayer) {
                LocalPlayer player = (LocalPlayer) sender;
                if (owners.contains(player) && !sender.hasPermission("worldguard.region.unlimited")) {
                    int maxRegionCount = WorldGuard.getInstance().getPlatform().getGlobalStateManager()
                            .get(world).getMaxRegionCount(player);
                    if (maxRegionCount >= 0 && manager.getRegionCountOfPlayer(player)
                            >= maxRegionCount) {
                        throw new CommandException("@wg:maxRegionsOwned@");
                    }
                }
            }
            if (region.getOwners().size() == 0) {
                boolean anyOwners = false;
                ProtectedRegion parent = region;
                while ((parent = parent.getParent()) != null) {
                    if (parent.getOwners().size() > 0) {
                        anyOwners = true;
                        break;
                    }
                }
                if (!anyOwners) {
                    sender.checkPermission("worldguard.region.addowner.unclaimed." + region.getId().toLowerCase());
                }
            }
            return owners;
        };
    }

    public void removeMember(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = checkWorld(args, sender, 'w'); // Get the world
        String id = args.getString(0);
        RegionManager manager = checkRegionManager(world);
        ProtectedRegion region = checkExistingRegion(manager, id, true);

        // Check permissions
        if (!getPermissionModel(sender).mayRemoveMembers(region)) {
            throw new CommandPermissionsException();
        }

        Callable<DefaultDomain> callable;
        if (args.hasFlag('a')) {
            callable = region::getMembers;
        } else {
            if (args.argsLength() < 2) {
                throw new CommandException("@wg:namesToRemove@");
            }

            // Resolve members asynchronously
            DomainInputResolver resolver = new DomainInputResolver(
                    WorldGuard.getInstance().getProfileService(), args.getParsedPaddedSlice(1, 0));
            callable = resolver;
        }

        final String description = BukkitMessages.template(
                "taskRemovingMembers", "region", region.getId(), "world", world.getName());
        AsyncCommandBuilder.wrap(callable, sender)
                .registerWithSupervisor(worldGuard.getSupervisor(), description)
                .setDelayMessage(TextComponent.of("@wg:waitQueryingPlayers@"))
                .onSuccess(TextComponent.of(BukkitMessages.template(
                        "membersRemoved", "region", region.getId())), region.getMembers()::removeAll)
                .onFailure(TextComponent.of("@wg:membersRemoveFailed@"), worldGuard.getExceptionConverter())
                .buildAndExec(worldGuard.getExecutorService());
    }

    public void removeOwner(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = checkWorld(args, sender, 'w'); // Get the world
        String id = args.getString(0);
        RegionManager manager = checkRegionManager(world);
        ProtectedRegion region = checkExistingRegion(manager, id, true);

        // Check permissions
        if (!getPermissionModel(sender).mayRemoveOwners(region)) {
            throw new CommandPermissionsException();
        }

        Callable<DefaultDomain> callable;
        if (args.hasFlag('a')) {
            callable = region::getOwners;
        } else {
            if (args.argsLength() < 2) {
                throw new CommandException("@wg:namesToRemove@");
            }

            // Resolve owners asynchronously
            DomainInputResolver resolver = new DomainInputResolver(
                    WorldGuard.getInstance().getProfileService(), args.getParsedPaddedSlice(1, 0));
            callable = resolver;
        }

        final String description = BukkitMessages.template(
                "taskRemovingOwners", "region", region.getId(), "world", world.getName());
        boolean preventLastOwnerRemoval = WorldGuard.getInstance().getPlatform()
                .getGlobalStateManager().get(world).preventLastOwnerRemoval;
        AsyncCommandBuilder.wrap(callable, sender)
                .registerWithSupervisor(worldGuard.getSupervisor(), description)
                .setDelayMessage(TextComponent.of("@wg:waitQueryingPlayers@"))
                .onSuccess((TextComponent) null, owners -> {
                    synchronized (region.getOwners()) {
                        if (preventLastOwnerRemoval && wouldRemoveLastOwner(region, owners)) {
                            sender.print(TextComponent.of(BukkitMessages.template(
                                    "lastOwnerRemovalDenied", "region", region.getId())));
                            return;
                        }
                        region.getOwners().removeAll(owners);
                    }
                    sender.print(TextComponent.of(BukkitMessages.template(
                            "ownersRemoved", "region", region.getId())));
                })
                .onFailure(TextComponent.of("@wg:ownersRemoveFailed@"), worldGuard.getExceptionConverter())
                .buildAndExec(worldGuard.getExecutorService());
    }

    static boolean wouldRemoveLastOwner(ProtectedRegion region, DefaultDomain removals) {
        DefaultDomain remaining = new DefaultDomain(region.getOwners());
        remaining.removeAll(removals);
        return remaining.size() == 0;
    }
}
