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

import static com.google.common.base.Preconditions.checkNotNull;

import com.sk89q.worldguard.commands.framework.CommandContext;
import com.sk89q.worldguard.commands.framework.CommandException;
import com.sk89q.worldguard.commands.framework.CommandPermissionsException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.command.util.AsyncCommandBuilder;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.formatting.component.ErrorFormat;
import com.sk89q.worldedit.util.formatting.component.LabelFormat;
import com.sk89q.worldedit.util.formatting.component.SubtleFormat;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.gamemode.GameModes;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitMessages;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.util.SelectionExpiry;
import com.sk89q.worldguard.bukkit.util.SelectionLimitTracker;
import com.sk89q.worldguard.commands.task.RegionAdder;
import com.sk89q.worldguard.commands.task.RegionLister;
import com.sk89q.worldguard.commands.task.RegionManagerLoader;
import com.sk89q.worldguard.commands.task.RegionManagerSaver;
import com.sk89q.worldguard.commands.task.RegionRemover;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.internal.permission.RegionPermissionModel;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.FlagValueCalculator;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.FlagContext;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.InvalidFlagFormat;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.RegionGroupFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion.CircularInheritanceException;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.util.WorldEditRegionConverter;
import com.sk89q.worldguard.session.Session;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Implements the /region commands for WorldGuard.
 */
@SuppressWarnings("deprecation")
public final class RegionCommands extends RegionCommandsBase {

    private final WorldGuard worldGuard;
    private final Map<UUID, ClaimExpansionUndo> claimExpansionUndos = new ConcurrentHashMap<>();

    public RegionCommands(WorldGuard worldGuard) {
        checkNotNull(worldGuard);
        this.worldGuard = worldGuard;
    }

    private static final TextComponent PASSTHROUGH_FLAG_WARNING = TextComponent.of("@wg:passthroughFlagWarning@");
    private static final TextComponent BUILD_FLAG_WARNING = TextComponent.of("@wg:buildFlagWarning@");

    /**
     * Defines a new region.
     * 
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void define(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        // Check permissions
        if (!getPermissionModel(sender).mayDefine()) {
            throw new CommandPermissionsException();
        }

        String id = checkRegionId(args.getString(0), false);

        World world = checkWorld(args, sender, 'w');
        RegionManager manager = checkRegionManager(world);

        checkRegionDoesNotExist(manager, id, true);

        ProtectedRegion region;

        if (args.hasFlag('g')) {
            region = new GlobalProtectedRegion(id);
        } else {
            region = checkRegionFromSelection(sender, id);
            if (sender instanceof LocalPlayer player) {
                enforceSelectionLimit(player, world, region, region.volume(), true);
            }
        }
        SelectionExpiry selectionExpiry = args.hasFlag('g')
                ? null : SelectionExpiry.capture(sender, world);

        WorldConfiguration wcfg = WorldGuard.getInstance().getPlatform().getGlobalStateManager().get(world);
        wcfg.newRegionDefaults.applyToNewRegion(region, manager);

        RegionAdder task = new RegionAdder(manager, region);
        task.addOwnersFromCommand(args, 2);

        final String description = BukkitMessages.template(
                "taskAddingRegion", "region", region.getId());
        AsyncCommandBuilder.wrap(task, sender)
                .registerWithSupervisor(worldGuard.getSupervisor(), description)
                .onSuccess((Component) null,
                        t -> {
                            sender.print(TextComponent.of(BukkitMessages.template(
                                    "regionCreated", "region", region.getId())));
                            warnAboutDimensions(sender, region);
                            informNewUser(sender, manager, region);
                            checkSpawnOverlap(sender, world, region);
                            if (selectionExpiry != null) {
                                selectionExpiry.schedule();
                            }
                        })
                .onFailure(TextComponent.of(BukkitMessages.template(
                        "regionAddFailed", "region", region.getId())), worldGuard.getExceptionConverter())
                .buildAndExec(worldGuard.getExecutorService());
    }

    /**
     * Re-defines a region with a new selection.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void redefine(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        String id = checkRegionId(args.getString(0), false);

        World world = checkWorld(args, sender, 'w');
        RegionManager manager = checkRegionManager(world);

        ProtectedRegion existing = checkExistingRegion(manager, id, false);

        // Check permissions
        if (!getPermissionModel(sender).mayRedefine(existing)) {
            throw new CommandPermissionsException();
        }

        ProtectedRegion region;

        if (args.hasFlag('g')) {
            region = new GlobalProtectedRegion(id);
        } else {
            region = checkRegionFromSelection(sender, id);
            if (sender instanceof LocalPlayer player) {
                enforceSelectionLimit(player, world, region, region.volume(), true);
            }
        }
        SelectionExpiry selectionExpiry = args.hasFlag('g')
                ? null : SelectionExpiry.capture(sender, world);

        region.copyFrom(existing);

        RegionAdder task = new RegionAdder(manager, region);

        final String description = BukkitMessages.template(
                "taskUpdatingRegion", "region", region.getId());
        AsyncCommandBuilder.wrap(task, sender)
                .registerWithSupervisor(worldGuard.getSupervisor(), description)
                .setDelayMessage(TextComponent.of(BukkitMessages.template(
                        "waitUpdatingRegion", "region", region.getId())))
                .onSuccess((Component) null,
                        t -> {
                            sender.print(TextComponent.of(BukkitMessages.template(
                                    "regionUpdated", "region", region.getId())));
                            warnAboutDimensions(sender, region);
                            informNewUser(sender, manager, region);
                            checkSpawnOverlap(sender, world, region);
                            if (selectionExpiry != null) {
                                selectionExpiry.schedule();
                            }
                        })
                .onFailure(TextComponent.of(BukkitMessages.template(
                        "regionUpdateFailed", "region", region.getId())), worldGuard.getExceptionConverter())
                .buildAndExec(worldGuard.getExecutorService());
    }

    /**
     * Claiming command for users.
     *
     * <p>This command is a joke and it needs to be rewritten. It was contributed
     * code :(</p>
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void claim(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        LocalPlayer player = worldGuard.checkPlayer(sender);
        RegionPermissionModel permModel = getPermissionModel(player);
        checkClaimPermission(permModel);
        String id = checkRegionId(args.getString(0), false);
        RegionManager manager = checkRegionManager(player.getWorld());
        checkRegionDoesNotExist(manager, id, false);

        ProtectedRegion region = checkRegionFromSelection(player, id);
        ProtectedRegion originalRegion = copyRegionGeometry(region);
        int originalVolume = region.volume();
        SelectionExpiry selectionExpiry = SelectionExpiry.capture(player, player.getWorld());
        WorldConfiguration wcfg = WorldGuard.getInstance().getPlatform().getGlobalStateManager().get(player.getWorld());

        enforceSelectionLimit(player, player.getWorld(), originalRegion, originalVolume, true);
        checkClaimRegionCount(player, permModel, manager, wcfg);
        checkClaimOverlap(player, region, manager, wcfg);
        int maxClaimVolume = wcfg.getMaxClaimVolume(player);
        if (!checkClaimVolume(player, permModel, region, maxClaimVolume)) {
            return;
        }

        if (!WorldGuardPlugin.inst().isClaimExpansionDisabled(player.getUniqueId())) {
            region = expandClaim(player, region, manager, wcfg, maxClaimVolume);
            SelectionLimitTracker.Decision decision = enforceSelectionLimit(
                    player, player.getWorld(), originalRegion, region.volume(), false);
            if (decision == SelectionLimitTracker.Decision.WITHOUT_EXPANSION) {
                player.print(TextComponent.of(BukkitMessages.template(
                        "selectionLimitExpansionSkipped",
                        "maximum", WorldGuardPlugin.inst().getConfigManager()
                                .selectionLimit.maximumVolume,
                        "expanded", region.volume())));
                region = originalRegion;
            }
        }
        completeClaim(player, id, region, manager, wcfg);
        if (!sameRegionGeometry(originalRegion, region)) {
            registerClaimExpansion(player, id, originalRegion, originalVolume, region,
                    wcfg.claimExpansionOfferMode.claim);
        }
        WorldGuardPlugin.inst().clearClaimExpansionPreference(player.getUniqueId());
        WorldGuardPlugin.inst().getSelectionLimitTracker().forget(player.getUniqueId());
        selectionExpiry.schedule();
    }

    private static SelectionLimitTracker.Decision enforceSelectionLimit(
            LocalPlayer player, World world, ProtectedRegion original,
            int expandedVolume, boolean clearDenied) throws CommandException {
        WorldGuardPlugin plugin = WorldGuardPlugin.inst();
        SelectionLimitTracker tracker = plugin.getSelectionLimitTracker();
        SelectionLimitTracker.Decision decision = tracker.evaluate(
                player, world, original, expandedVolume);
        if (decision == SelectionLimitTracker.Decision.DENY) {
            if (clearDenied) {
                SelectionExpiry.capture(player, world).clear();
            }
            throw new CommandException(BukkitMessages.template(
                    "selectionLimitExceeded",
                    "maximum", plugin.getConfigManager().selectionLimit.maximumVolume,
                    "current", original.volume()));
        }
        if (decision == SelectionLimitTracker.Decision.CONFIRM) {
            throw new CommandException(BukkitMessages.template(
                    "selectionLimitConfirmationRequired",
                    "maximum", plugin.getConfigManager().selectionLimit.maximumVolume,
                    "current", original.volume()));
        }
        return decision;
    }

    private static void checkClaimPermission(RegionPermissionModel permModel) throws CommandPermissionsException {
        if (!permModel.mayClaim()) {
            throw new CommandPermissionsException();
        }
    }

    private static void checkClaimRegionCount(LocalPlayer player, RegionPermissionModel permModel,
                                              RegionManager manager, WorldConfiguration wcfg)
            throws CommandException {
        if (permModel.mayClaimRegionsUnbounded()) {
            return;
        }
        int maxRegionCount = wcfg.getMaxRegionCount(player);
        if (maxRegionCount >= 0 && manager.getRegionCountOfPlayer(player) >= maxRegionCount) {
            throw new CommandException(BukkitMessages.template(
                    "claimRegionLimitReached", "limit", maxRegionCount));
        }
    }

    private static void checkClaimOverlap(LocalPlayer player, ProtectedRegion region,
                                          RegionManager manager, WorldConfiguration wcfg)
            throws CommandException {
        ApplicableRegionSet regions = manager.getApplicableRegions(region);
        if (regions.size() == 0) {
            if (wcfg.claimOnlyInsideExistingRegions) {
                throw new CommandException("@wg:claimInside@");
            }
            return;
        }
        if (regions.isOwnerOfAll(player)) {
            return;
        }
        ProtectedRegion conflicting = firstUnownedRegion(regions, player);
        BlockVector3 intersection = RegionOverlapDetails.findIntersectionPoint(region, conflicting);
        throw new CommandException(BukkitMessages.template(
                "regionOverlap",
                "region", conflicting.getId(),
                "owners", inheritedOwners(conflicting),
                "x", intersection.x(),
                "y", intersection.y(),
                "z", intersection.z()));
    }

    private static boolean checkClaimVolume(LocalPlayer player, RegionPermissionModel permModel,
                                            ProtectedRegion region, int maxClaimVolume)
            throws CommandException {
        if (maxClaimVolume == Integer.MAX_VALUE) {
            throw new CommandException(BukkitMessages.template(
                    "claimVolumeInvalid", "maximum", Integer.MAX_VALUE));
        }
        if (permModel.mayClaimRegionsUnbounded() || region.volume() <= maxClaimVolume) {
            return true;
        }
        player.printError(TextComponent.of(BukkitMessages.template(
                "claimTooLarge", "maximum", maxClaimVolume, "current", region.volume())));
        return false;
    }

    private static ProtectedRegion expandClaim(LocalPlayer player, ProtectedRegion region,
                                                RegionManager manager, WorldConfiguration wcfg,
                                                int maxClaimVolume) {
        if (region instanceof ProtectedCuboidRegion cuboidRegion) {
            return ClaimRegionExpander.expand(
                    cuboidRegion,
                    player.getWorld().getMinimumPoint(),
                    player.getWorld().getMaximumPoint(),
                    wcfg.claimExpansion,
                    maxClaimVolume,
                    candidate -> manager.getApplicableRegions(candidate).isOwnerOfAll(player));
        }
        if (region instanceof ProtectedPolygonalRegion polygonalRegion) {
            return ClaimRegionExpander.expand(
                    polygonalRegion,
                    player.getWorld().getMinimumPoint(),
                    player.getWorld().getMaximumPoint(),
                    wcfg.claimExpansion,
                    maxClaimVolume,
                    candidate -> manager.getApplicableRegions(candidate).isOwnerOfAll(player));
        }
        return region;
    }

    private static void completeClaim(LocalPlayer player, String id, ProtectedRegion region,
                                      RegionManager manager, WorldConfiguration wcfg) {
        wcfg.newRegionDefaults.applyToNewRegion(region, manager);
        region.getOwners().addPlayer(player);
        manager.addRegion(region);
        player.print(TextComponent.of(BukkitMessages.template("regionClaimed", "region", id)));
    }

    private void registerClaimExpansion(LocalPlayer player, String id, ProtectedRegion originalRegion,
                                        int originalVolume, ProtectedRegion region, boolean sendOffer) {
        claimExpansionUndos.put(player.getUniqueId(), new ClaimExpansionUndo(
                id, player.getWorld(), originalRegion, copyRegionGeometry(region)));
        if (sendOffer) {
            player.print(TextComponent.of(BukkitMessages.template(
                    "claimExpansionApplied",
                    "region", id,
                    "original", originalVolume,
                    "expanded", region.volume())));
        }
    }

    public void toggleClaimExpansion(CommandContext args, Actor sender) throws CommandException {
        LocalPlayer player = worldGuard.checkPlayer(sender);
        checkClaimPermission(getPermissionModel(player));
        checkRegionFromSelection(player, "selection_preview");
        boolean disabled = WorldGuardPlugin.inst().toggleClaimExpansion(player.getUniqueId());
        player.print(TextComponent.of(BukkitMessages.template(
                disabled ? "claimExpansionDisabled" : "claimExpansionEnabled")));
    }

    public void confirmSelection(CommandContext args, Actor sender) throws CommandException {
        LocalPlayer player = worldGuard.checkPlayer(sender);
        World world = player.getWorld();
        ProtectedRegion selection = checkRegionFromSelection(player, "selection_confirmation");
        SelectionLimitTracker.ConfirmationResult result = WorldGuardPlugin.inst()
                .getSelectionLimitTracker().confirm(player, world, selection);
        String message = switch (result) {
            case CONFIRMED -> "selectionLimitConfirmed";
            case NOT_ALLOWED -> "selectionLimitBypassUnavailable";
            case NOT_REQUIRED -> "selectionLimitConfirmationNotRequired";
        };
        player.print(TextComponent.of(BukkitMessages.template(message)));
    }

    public void undoClaimExpansion(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);
        LocalPlayer player = worldGuard.checkPlayer(sender);
        String id = checkRegionId(args.getString(0), false);
        ClaimExpansionUndo undo = claimExpansionUndos.get(player.getUniqueId());
        if (undo == null || !undo.regionId.equals(id)) {
            throw new CommandException(BukkitMessages.template(
                    "claimExpansionUnavailable", "region", id));
        }

        RegionManager manager = checkRegionManager(undo.world);
        ProtectedRegion current = manager.getRegion(id);
        if (current == null || !current.getOwners().contains(player)
                || !sameRegionGeometry(current, undo.expanded)) {
            claimExpansionUndos.remove(player.getUniqueId(), undo);
            throw new CommandException(BukkitMessages.template(
                    "claimExpansionChanged", "region", id));
        }

        ProtectedRegion restored = copyRegionGeometry(undo.original);
        restored.copyFrom(current);
        manager.addRegion(restored);
        claimExpansionUndos.remove(player.getUniqueId(), undo);
        player.print(TextComponent.of(BukkitMessages.template(
                "claimExpansionUndone", "region", id)));
    }

    private static ProtectedRegion copyRegionGeometry(ProtectedRegion region) {
        if (region instanceof ProtectedCuboidRegion cuboid) {
            return new ProtectedCuboidRegion(
                    cuboid.getId(), cuboid.getMinimumPoint(), cuboid.getMaximumPoint());
        }
        if (region instanceof ProtectedPolygonalRegion polygon) {
            return new ProtectedPolygonalRegion(
                    polygon.getId(), polygon.getPoints(),
                    polygon.getMinimumPoint().y(), polygon.getMaximumPoint().y());
        }
        throw new IllegalArgumentException(region.getClass().getName());
    }

    private static boolean sameRegionGeometry(ProtectedRegion first, ProtectedRegion second) {
        return first.getClass() == second.getClass()
                && first.getMinimumPoint().equals(second.getMinimumPoint())
                && first.getMaximumPoint().equals(second.getMaximumPoint())
                && first.getPoints().equals(second.getPoints());
    }

    private record ClaimExpansionUndo(
            String regionId, World world, ProtectedRegion original, ProtectedRegion expanded) {
    }

    private static ProtectedRegion firstUnownedRegion(ApplicableRegionSet regions, LocalPlayer player) {
        ProtectedRegion result = null;
        for (ProtectedRegion candidate : regions) {
            if (!candidate.isOwner(player)
                    && (result == null || candidate.getId().compareToIgnoreCase(result.getId()) < 0)) {
                result = candidate;
            }
        }
        if (result == null) {
            throw new IllegalStateException("Expected an unowned intersecting region");
        }
        return result;
    }

    private static String inheritedOwners(ProtectedRegion region) {
        List<String> owners = new ArrayList<>();
        ProtectedRegion current = region;
        while (current != null) {
            String currentOwners = current.getOwners()
                    .toUserFriendlyString(WorldGuard.getInstance().getProfileCache())
                    .replace("*", "");
            if (!currentOwners.isEmpty() && !owners.contains(currentOwners)) {
                owners.add(currentOwners);
            }
            current = current.getParent();
        }
        return owners.isEmpty() ? "-" : String.join("; ", owners);
    }

    /**
     * Get a WorldEdit selection from a region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void select(CommandContext args, Actor sender) throws CommandException {
        World world = checkWorld(args, sender, 'w');
        RegionManager manager = checkRegionManager(world);
        ProtectedRegion existing;

        // If no arguments were given, get the region that the player is inside
        if (args.argsLength() == 0) {
            LocalPlayer player = worldGuard.checkPlayer(sender);
            if (!player.getWorld().equals(world)) { // confusing to get current location regions in another world
                throw new CommandException("@wg:specifyRegionName@"); // just don't allow that
            }
            world = player.getWorld();
            existing = checkRegionStandingIn(manager, player, "/rg select -w \"" + world.getName() + "\" %id%");
        } else {
            existing = checkExistingRegion(manager, args.getString(0), false);
        }

        // Check permissions
        if (!getPermissionModel(sender).maySelect(existing)) {
            throw new CommandPermissionsException();
        }

        // Select
        setPlayerSelection(sender, existing, world);
    }

    /**
     * Get information about a region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void info(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = checkWorld(args, sender, 'w'); // Get the world
        WorldConfiguration wcfg = WorldGuard.getInstance().getPlatform().getGlobalStateManager().get(world);
        RegionPermissionModel permModel = getPermissionModel(sender);

        // Lookup the existing region
        RegionManager manager = checkRegionManager(world);
        ProtectedRegion existing;

        if (args.argsLength() == 0) { // Get region from where the player is
            if (!(sender instanceof LocalPlayer)) {
                throw new CommandException("@wg:specifyRegionInfo@");
            }

            existing = checkRegionStandingIn(manager, (LocalPlayer) sender, true,
                    "/rg info -w \"" + world.getName() + "\" %id%" + (args.hasFlag('u') ? " -u" : "") + (args.hasFlag('s') ? " -s" : ""));
            if (ProtectedRegion.GLOBAL_REGION.equals(existing.getId()) && !wcfg.showGlobalRegionInfo) {
                return;
            }
        } else { // Get region from the ID
            existing = checkExistingRegion(manager, args.getString(0), true);
        }

        // Check permissions
        if (!permModel.mayLookup(existing)) {
            throw new CommandPermissionsException();
        }

        // Let the player select the region
        if (args.hasFlag('s')) {
            // Check permissions
            if (!permModel.maySelect(existing)) {
                throw new CommandPermissionsException();
            }

            setPlayerSelection(worldGuard.checkPlayer(sender), existing, world);
        }

        // Print region information
        RegionPrintoutBuilder printout = new RegionPrintoutBuilder(world.getName(), existing,
                args.hasFlag('u') ? null : WorldGuard.getInstance().getProfileCache(), sender,
                wcfg.showPlayerUuidsInRegionInfo, wcfg.showRegionBlockCountInInfo,
                wcfg.regionInfoNumberGroupingEnabled
                        ? wcfg.regionInfoNumberGroupingSeparator : "");

        AsyncCommandBuilder.wrap(printout, sender)
                .registerWithSupervisor(WorldGuard.getInstance().getSupervisor(), "@wg:taskFetchingRegionInfo@")
                .setDelayMessage(TextComponent.of("@wg:waitFetchingRegionInfo@"))
                .onSuccess((Component) null, component -> {
                    sender.print(component);
                    checkSpawnOverlap(sender, world, existing);
                })
                .onFailure(TextComponent.of("@wg:regionInfoFetchFailed@"), WorldGuard.getInstance().getExceptionConverter())
                .buildAndExec(WorldGuard.getInstance().getExecutorService());
    }

    /**
     * List regions.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void list(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = checkWorld(args, sender, 'w'); // Get the world
        WorldConfiguration wcfg = WorldGuard.getInstance().getPlatform().getGlobalStateManager().get(world);
        ListParameters parameters = wcfg.regionListCommandMode == 2
                ? parseScopedList(args, sender) : parseLegacyList(args, sender);

        RegionManager manager = checkRegionManager(world);
        RegionLister task = new RegionLister(manager, sender, world.getName());
        task.setPage(parameters.page);
        task.setCommandScope(parameters.commandScope);
        if (parameters.ownedBy != null) {
            task.filterOwnedByName(parameters.ownedBy, args.hasFlag('n'));
        }

        if (args.hasFlag('s')) {
            ProtectedRegion existing = checkRegionFromSelection(sender, "tmp");
            task.filterByIntersecting(existing);
        }

        // -i string is in region id
        if (args.hasFlag('i')) {
            task.filterIdByMatch(args.getFlag('i'));
        }

        AsyncCommandBuilder.wrap(task, sender)
                .registerWithSupervisor(WorldGuard.getInstance().getSupervisor(), "@wg:taskFetchingRegionList@")
                .setDelayMessage(TextComponent.of("@wg:waitFetchingRegionList@"))
                .onFailure(TextComponent.of("@wg:regionListFetchFailed@"), WorldGuard.getInstance().getExceptionConverter())
                .buildAndExec(WorldGuard.getInstance().getExecutorService());
    }

    private ListParameters parseScopedList(CommandContext args, Actor sender) throws CommandException {
        if (args.argsLength() == 0) {
            throw new CommandException("@wg:regionListScopeRequired@");
        }
        String scope = args.getString(0).toLowerCase(Locale.ROOT);
        String ownedBy;
        if (scope.equals("my")) {
            ownedBy = worldGuard.checkPlayer(sender).getName();
        } else if (scope.equals("global")) {
            ownedBy = null;
        } else {
            throw new CommandException("@wg:regionListScopeInvalid@");
        }
        if (args.hasFlag('p')) ownedBy = args.getFlag('p');
        requireListPermission(sender, ownedBy);
        return new ListParameters(ownedBy, scope, Math.max(1, args.getInteger(1, 1)));
    }

    private ListParameters parseLegacyList(CommandContext args, Actor sender) throws CommandException {
        if (args.argsLength() > 1) {
            throw new CommandException("@wg:regionListLegacyArguments@");
        }
        String ownedBy = args.hasFlag('p') ? args.getFlag('p') : null;
        if (!getPermissionModel(sender).mayList(ownedBy)) {
            ownedBy = sender.getName();
            requireListPermission(sender, ownedBy);
        }
        return new ListParameters(ownedBy, null, Math.max(1, args.getInteger(0, 1)));
    }

    private void requireListPermission(Actor sender, @Nullable String ownedBy)
            throws CommandPermissionsException {
        if (!getPermissionModel(sender).mayList(ownedBy)) {
            throw new CommandPermissionsException();
        }
    }

    /**
     * Set a flag.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void flag(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = checkWorld(args, sender, 'w'); // Get the world
        String flagName = args.getString(1);
        String value = readFlagValue(args);
        FlagRegistry flagRegistry = WorldGuard.getInstance().getFlagRegistry();
        RegionPermissionModel permModel = getPermissionModel(sender);
        RegionManager manager = checkRegionManager(world);
        ProtectedRegion existing = checkExistingRegion(manager, args.getString(0), true);
        if (!permModel.maySetFlag(existing)) {
            throw new CommandPermissionsException();
        }

        Flag<?> foundFlag = Flags.fuzzyMatchFlag(flagRegistry, flagName);
        if (foundFlag == null) {
            suggestFlags(flagRegistry, permModel, existing, world, sender, flagName);
            return;
        }
        warnAboutDangerousFlag(sender, foundFlag, value);
        if (!permModel.maySetFlag(existing, foundFlag, value)) {
            throw new CommandPermissionsException();
        }

        RegionGroup groupValue = parseGroupValue(args, sender, existing, foundFlag);
        applyFlagValue(args, sender, existing, foundFlag, value);
        applyGroupValue(sender, existing, foundFlag, groupValue);
        if (args.hasFlag('h')) {
            int page = args.getFlagInteger('h');
            sendFlagHelper(sender, world, existing, permModel, page);
        } else {
            RegionPrintoutBuilder printout = new RegionPrintoutBuilder(world.getName(), existing, null, sender);
            printout.append(SubtleFormat.wrap("@wg:currentFlags@"));
            printout.appendFlagsList(false);
            printout.send(sender);
            checkSpawnOverlap(sender, world, existing);
        }
    }

    public void flagHelper(CommandContext args, Actor sender) throws CommandException {
        World world = checkWorld(args, sender, 'w'); // Get the world

        // Lookup the existing region
        RegionManager manager = checkRegionManager(world);
        ProtectedRegion region;
        if (args.argsLength() == 0) { // Get region from where the player is
            if (!(sender instanceof LocalPlayer)) {
                throw new CommandException("@wg:specifyRegionFlags@");
            }

            region = checkRegionStandingIn(manager, (LocalPlayer) sender, true,
                    "/rg flags -w \"" + world.getName() + "\" %id%");
        } else { // Get region from the ID
            region = checkExistingRegion(manager, args.getString(0), true);
        }

        final RegionPermissionModel perms = getPermissionModel(sender);
        if (!perms.mayLookup(region)) {
            throw new CommandPermissionsException();
        }
        int page = args.hasFlag('p') ? args.getFlagInteger('p') : 1;

        sendFlagHelper(sender, world, region, perms, page);
    }

    private static void sendFlagHelper(Actor sender, World world, ProtectedRegion region, RegionPermissionModel perms, int page) {
        final FlagHelperBox flagHelperBox = new FlagHelperBox(world, region, perms);
        flagHelperBox.setComponentsPerPage(18);
        if (!sender.isPlayer()) {
            flagHelperBox.tryMonoSpacing();
        }
        AsyncCommandBuilder.wrap(() -> {
                    if (checkSpawnOverlap(sender, world, region)) {
                        flagHelperBox.setComponentsPerPage(15);
                    }
                    return flagHelperBox.create(page);
                }, sender)
                .onSuccess((Component) null, sender::print)
                .onFailure(TextComponent.of("@wg:regionFlagsFetchFailed@"), WorldGuard.getInstance().getExceptionConverter())
                .buildAndExec(WorldGuard.getInstance().getExecutorService());
    }

    /**
     * Set the priority of a region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void setPriority(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = checkWorld(args, sender, 'w'); // Get the world
        int priority = args.getInteger(1);

        // Lookup the existing region
        RegionManager manager = checkRegionManager(world);
        ProtectedRegion existing = checkExistingRegion(manager, args.getString(0), false);

        // Check permissions
        if (!getPermissionModel(sender).maySetPriority(existing)) {
            throw new CommandPermissionsException();
        }

        existing.setPriority(priority);

        sender.print(TextComponent.of(BukkitMessages.template(
                "prioritySet", "region", existing.getId(), "priority", priority)));
        checkSpawnOverlap(sender, world, existing);
    }

    /**
     * Set the parent of a region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void setParent(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = checkWorld(args, sender, 'w'); // Get the world
        ProtectedRegion parent;
        ProtectedRegion child;

        // Lookup the existing region
        RegionManager manager = checkRegionManager(world);

        // Get parent and child
        child = checkExistingRegion(manager, args.getString(0), false);
        if (args.argsLength() == 2) {
            parent = checkExistingRegion(manager, args.getString(1), false);
        } else {
            parent = null;
        }

        // Check permissions
        if (!getPermissionModel(sender).maySetParent(child, parent)) {
            throw new CommandPermissionsException();
        }

        try {
            child.setParent(parent);
        } catch (CircularInheritanceException e) {
            // Tell the user what's wrong
            RegionPrintoutBuilder printout = new RegionPrintoutBuilder(world.getName(), parent, null, sender);
            assert parent != null;
            printout.append(ErrorFormat.wrap(BukkitMessages.template(
                    "circularInheritance", "parent", parent.getId(), "child", child.getId()))).newline();
            printout.append(SubtleFormat.wrap("@wg:currentInheritance@")).newline();
            printout.appendParentTree(true);
            printout.send(sender);
            return;
        }

        // Tell the user the current inheritance
        RegionPrintoutBuilder printout = new RegionPrintoutBuilder(world.getName(), child, null, sender);
        printout.append(TextComponent.of(BukkitMessages.template(
                "inheritanceSet", "region", child.getId())));
        if (parent != null) {
            printout.newline();
            printout.append(SubtleFormat.wrap("@wg:currentInheritance@")).newline();
            printout.appendParentTree(true);
        } else {
            printout.append(LabelFormat.wrap(" @wg:regionOrphaned@"));
        }
        printout.send(sender);
    }

    /**
     * Remove a region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void remove(CommandContext args, Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = checkWorld(args, sender, 'w'); // Get the world
        boolean removeChildren = args.hasFlag('f');
        boolean unsetParent = args.hasFlag('u');

        // Lookup the existing region
        RegionManager manager = checkRegionManager(world);
        ProtectedRegion existing = checkExistingRegion(manager, args.getString(0), true);

        // Check permissions
        if (!getPermissionModel(sender).mayDelete(existing)) {
            throw new CommandPermissionsException();
        }

        RegionRemover task = new RegionRemover(manager, existing);

        if (removeChildren && unsetParent) {
            throw new CommandException("@wg:incompatibleParentFlags@");
        } else if (removeChildren) {
            task.setRemovalStrategy(RemovalStrategy.REMOVE_CHILDREN);
        } else if (unsetParent) {
            task.setRemovalStrategy(RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
        }

        final String description = BukkitMessages.template(
                "taskRemovingRegion", "region", existing.getId(), "world", world.getName());
        AsyncCommandBuilder.wrap(task, sender)
                .registerWithSupervisor(WorldGuard.getInstance().getSupervisor(), description)
                .setDelayMessage(TextComponent.of("@wg:waitRemovingRegion@"))
                .onSuccess((Component) null, removed -> sender.print(TextComponent.of(
                        BukkitMessages.template("regionsRemoved", "regions",
                                removed.stream().map(ProtectedRegion::getId)
                                        .collect(Collectors.joining(", "))))))
                .onFailure(TextComponent.of("@wg:regionRemoveFailed@"), WorldGuard.getInstance().getExceptionConverter())
                .buildAndExec(WorldGuard.getInstance().getExecutorService());
    }

    /**
     * Reload the region database.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void load(CommandContext args, final Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = null;
        try {
            world = checkWorld(args, sender, 'w'); // Get the world
        } catch (CommandException ignored) {
            // assume the user wants to reload all worlds
        }

        // Check permissions
        if (!getPermissionModel(sender).mayForceLoadRegions()) {
            throw new CommandPermissionsException();
        }

        if (world != null) {
            RegionManager manager = checkRegionManager(world);

            final String description = BukkitMessages.template(
                    "taskLoadingRegionData", "world", world.getName());
            AsyncCommandBuilder.wrap(new RegionManagerLoader(manager), sender)
                    .registerWithSupervisor(worldGuard.getSupervisor(), description)
                    .setDelayMessage(TextComponent.of(BukkitMessages.template(
                            "waitLoadingRegionData", "world", world.getName())))
                    .onSuccess(TextComponent.of(BukkitMessages.template(
                            "regionDataLoaded", "world", world.getName())), null)
                    .onFailure(TextComponent.of(BukkitMessages.template(
                            "regionDataLoadFailed", "world", world.getName())), worldGuard.getExceptionConverter())
                    .buildAndExec(worldGuard.getExecutorService());
        } else {
            // Load regions for all worlds
            List<RegionManager> managers = new ArrayList<>();

            for (World w : WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.GAME_HOOKS).getWorlds()) {
                RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(w);
                if (manager != null) {
                    managers.add(manager);
                }
            }

            AsyncCommandBuilder.wrap(new RegionManagerLoader(managers), sender)
                    .registerWithSupervisor(worldGuard.getSupervisor(), "@wg:taskLoadingAllRegions@")
                    .setDelayMessage(TextComponent.of("@wg:waitLoadingAllRegions@"))
                    .onSuccess(TextComponent.of("@wg:allRegionDataLoaded@"), null)
                    .onFailure(TextComponent.of("@wg:allRegionDataLoadFailed@"), worldGuard.getExceptionConverter())
                    .buildAndExec(worldGuard.getExecutorService());
        }
    }

    /**
     * Re-save the region database.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void save(CommandContext args, final Actor sender) throws CommandException {
        warnAboutSaveFailures(sender);

        World world = null;
        try {
            world = checkWorld(args, sender, 'w'); // Get the world
        } catch (CommandException ignored) {
            // assume user wants to save all worlds
        }

        // Check permissions
        if (!getPermissionModel(sender).mayForceSaveRegions()) {
            throw new CommandPermissionsException();
        }

        if (world != null) {
            RegionManager manager = checkRegionManager(world);

            final String description = BukkitMessages.template(
                    "taskSavingRegionData", "world", world.getName());
            AsyncCommandBuilder.wrap(new RegionManagerSaver(manager), sender)
                    .registerWithSupervisor(worldGuard.getSupervisor(), description)
                    .setDelayMessage(TextComponent.of(BukkitMessages.template(
                            "waitSavingRegionData", "world", world.getName())))
                    .onSuccess(TextComponent.of(BukkitMessages.template(
                            "regionDataSaved", "world", world.getName())), null)
                    .onFailure(TextComponent.of(BukkitMessages.template(
                            "regionDataSaveFailed", "world", world.getName())), worldGuard.getExceptionConverter())
                    .buildAndExec(worldGuard.getExecutorService());
        } else {
            // Save for all worlds
            List<RegionManager> managers = new ArrayList<>();

            final RegionContainer regionContainer = worldGuard.getPlatform().getRegionContainer();
            for (World w : WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.GAME_HOOKS).getWorlds()) {
                RegionManager manager = regionContainer.get(w);
                if (manager != null) {
                    managers.add(manager);
                }
            }

            AsyncCommandBuilder.wrap(new RegionManagerSaver(managers), sender)
                    .registerWithSupervisor(worldGuard.getSupervisor(), "@wg:taskSavingAllRegions@")
                    .setDelayMessage(TextComponent.of("@wg:waitSavingAllRegions@"))
                    .onSuccess(TextComponent.of("@wg:allRegionDataSaved@"), null)
                    .onFailure(TextComponent.of("@wg:allRegionDataSaveFailed@"), worldGuard.getExceptionConverter())
                    .buildAndExec(worldGuard.getExecutorService());
        }
    }

    /**
     * Teleport to a region
     * 
     * @param args the arguments
     * @param sender the sender
     * @throws CommandException any error
     */
    public void teleport(CommandContext args, Actor sender) throws CommandException {
        LocalPlayer player = worldGuard.checkPlayer(sender);
        Location teleportLocation;

        // Lookup the existing region
        World world = checkWorld(args, player, 'w');
        RegionManager regionManager = checkRegionManager(world);
        ProtectedRegion existing = checkExistingRegion(regionManager, args.getString(0), true);

        // Check permissions
        if (!getPermissionModel(player).mayTeleportTo(existing)) {
            throw new CommandPermissionsException();
        }

        // -s for spawn location
        if (args.hasFlag('s')) {
            teleportLocation = FlagValueCalculator.getEffectiveFlagOf(existing, Flags.SPAWN_LOC, player);
            
            if (teleportLocation == null) {
                throw new CommandException(
                        "@wg:noSpawnPoint@");
            }
        } else if (args.hasFlag('c')) {
            // Check permissions
            if (!getPermissionModel(player).mayTeleportToCenter(existing)) {
                throw new CommandPermissionsException();
            }
            Region region = WorldEditRegionConverter.convertToRegion(existing);
            if (region == null || region.getCenter() == null) {
                throw new CommandException("@wg:noCenterPoint@");
            }
            if (player.getGameMode() == GameModes.SPECTATOR) {
                teleportLocation = new Location(world, region.getCenter(), 0, 0);
            } else {
                // Center teleport remains spectator-only because the available free-position
                // search cannot guarantee that its result stays inside the selected region.
                throw new CommandException("@wg:centerSpectatorOnly@");
            }
        } else {
            teleportLocation = FlagValueCalculator.getEffectiveFlagOf(existing, Flags.TELE_LOC, player);
            
            if (teleportLocation == null) {
                throw new CommandException("@wg:noTeleportPoint@");
            }
        }

        String message = FlagValueCalculator.getEffectiveFlagOf(existing, Flags.TELE_MESSAGE, player);

        // If the flag isn't set, use the default message
        // If message.isEmpty(), no message is sent by LocalPlayer#teleport(...)
        if (message == null) {
            message = Flags.TELE_MESSAGE.getDefault();
        }

        player.teleport(teleportLocation,
                BukkitMessages.replacePlaceholder(message, "id", existing.getId()),
                BukkitMessages.template("teleportFailed", "region", existing.getId()));
    }

    public void toggleBypass(CommandContext args, Actor sender) throws CommandException {
        LocalPlayer player = worldGuard.checkPlayer(sender);
        if (!player.hasPermission("worldguard.region.toggle-bypass")) {
            throw new CommandPermissionsException();
        }
        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);
        boolean shouldEnableBypass;
        if (args.argsLength() > 0) {
            String arg1 = args.getString(0);
            if (!arg1.equalsIgnoreCase("on") && !arg1.equalsIgnoreCase("off")) {
                throw new CommandException("@wg:bypassArguments@");
            }
            shouldEnableBypass = arg1.equalsIgnoreCase("on");
        } else {
            shouldEnableBypass = session.hasBypassDisabled();
        }
        if (shouldEnableBypass) {
            session.setBypassDisabled(false);
            player.print(TextComponent.of("@wg:bypassEnabled@"));
        } else {
            session.setBypassDisabled(true);
            player.print(TextComponent.of("@wg:bypassDisabled@"));
        }
    }

    private static final class ListParameters {
        public final String ownedBy;
        public final String commandScope;
        public final int page;

        private ListParameters(@Nullable String ownedBy, @Nullable String commandScope, int page) {
            this.ownedBy = ownedBy;
            this.commandScope = commandScope;
            this.page = page;
        }
    }

    @Nullable
    private String readFlagValue(CommandContext args) throws CommandException {
        String value = args.argsLength() >= 3 ? args.getJoinedStrings(2) : null;
        if (!args.hasFlag('e')) return value;
        if (value != null) throw new CommandException("@wg:flagValueWithEmpty@");
        return "";
    }

    private void suggestFlags(
            FlagRegistry registry,
            RegionPermissionModel permissions,
            ProtectedRegion region,
            World world,
            Actor sender,
            String flagName) {
        AsyncCommandBuilder.wrap(new FlagListBuilder(registry, permissions, region, world,
                        region.getId(), sender, flagName), sender)
                .registerWithSupervisor(WorldGuard.getInstance().getSupervisor(),
                        "Flag list for invalid flag command.")
                .onSuccess((Component) null, sender::print)
                .onFailure((Component) null, WorldGuard.getInstance().getExceptionConverter())
                .buildAndExec(WorldGuard.getInstance().getExecutorService());
    }

    private void warnAboutDangerousFlag(Actor sender, Flag<?> flag, @Nullable String value) {
        if (value == null) return;
        if (flag == Flags.BUILD || flag == Flags.BLOCK_BREAK || flag == Flags.BLOCK_PLACE) {
            sender.print(BUILD_FLAG_WARNING);
        } else if (flag == Flags.PASSTHROUGH) {
            sender.print(PASSTHROUGH_FLAG_WARNING);
        }
    }

    @Nullable
    private RegionGroup parseGroupValue(
            CommandContext args, Actor sender, ProtectedRegion region, Flag<?> flag)
            throws CommandException {
        if (!args.hasFlag('g')) return null;
        RegionGroupFlag groupFlag = flag.getRegionGroupFlag();
        if (groupFlag == null) {
            throw new CommandException(BukkitMessages.template(
                    "flagGroupUnsupported", "flag", flag.getName()));
        }
        try {
            return groupFlag.parseInput(FlagContext.create()
                    .setSender(sender).setInput(args.getFlag('g'))
                    .setObject("region", region).build());
        } catch (InvalidFlagFormat e) {
            throw invalidFlagValue(e);
        }
    }

    private void applyFlagValue(
            CommandContext args,
            Actor sender,
            ProtectedRegion region,
            Flag<?> flag,
            @Nullable String value) throws CommandException {
        if (value != null) {
            String parsed;
            try {
                parsed = setFlag(region, flag, sender, value).toString();
            } catch (InvalidFlagFormat e) {
                throw invalidFlagValue(e);
            }
            if (!args.hasFlag('h')) {
                sender.print(TextComponent.of(BukkitMessages.template(
                        "flagSet", "flag", flag.getName(), "region", region.getId(), "value", parsed)));
            }
        } else if (!args.hasFlag('g')) {
            region.setFlag(flag, null);
            RegionGroupFlag groupFlag = flag.getRegionGroupFlag();
            if (groupFlag != null) region.setFlag(groupFlag, null);
            if (!args.hasFlag('h')) {
                sender.print(TextComponent.of(BukkitMessages.template(
                        "flagRemoved", "flag", flag.getName(), "region", region.getId())));
            }
        }
    }

    private CommandException invalidFlagValue(InvalidFlagFormat exception) {
        return new CommandException(BukkitMessages.template(
                "flagValueInvalid", "error", String.valueOf(exception.getMessage())));
    }

    private void applyGroupValue(
            Actor sender, ProtectedRegion region, Flag<?> flag, @Nullable RegionGroup groupValue) {
        if (groupValue == null) return;
        RegionGroupFlag groupFlag = flag.getRegionGroupFlag();
        if (groupValue == groupFlag.getDefault()) {
            region.setFlag(groupFlag, null);
            sender.print(TextComponent.of(BukkitMessages.template(
                    "groupFlagReset", "flag", flag.getName())));
        } else {
            region.setFlag(groupFlag, groupValue);
            sender.print(TextComponent.of(BukkitMessages.template(
                    "groupFlagSet", "flag", flag.getName(), "group", groupValue)));
        }
    }

    private static class FlagListBuilder implements Callable<Component> {
        private final FlagRegistry flagRegistry;
        private final RegionPermissionModel permModel;
        private final ProtectedRegion existing;
        private final World world;
        private final String regionId;
        private final Actor sender;
        private final String flagName;

        FlagListBuilder(FlagRegistry flagRegistry, RegionPermissionModel permModel, ProtectedRegion existing,
                        World world, String regionId, Actor sender, String flagName) {
            this.flagRegistry = flagRegistry;
            this.permModel = permModel;
            this.existing = existing;
            this.world = world;
            this.regionId = regionId;
            this.sender = sender;
            this.flagName = flagName;
        }

        @Override
        public Component call() {
            ArrayList<String> flagList = new ArrayList<>();

            // Need to build a list
            for (Flag<?> flag : flagRegistry) {
                // Can the user set this flag?
                if (!permModel.maySetFlag(existing, flag)) {
                    continue;
                }

                flagList.add(flag.getName());
            }

            Collections.sort(flagList);

            final TextComponent.Builder builder = TextComponent.builder("@wg:availableFlags@");
            for (int i = 0; i < flagList.size(); i++) {
                String flag = flagList.get(i);
                builder.append(TextComponent.of(BukkitMessages.template(
                        "availableFlag",
                        "world", world.getName(),
                        "region", regionId,
                        "flag", flag)));
                if (i < flagList.size() - 1) {
                    builder.append(TextComponent.of(", "));
                }
            }

            Component ret = TextComponent.of(BukkitMessages.template(
                            "unknownFlagFull", "flag", flagName))
                    .append(TextComponent.newline())
                    .append(builder.build());
            if (sender.isPlayer()) {
                return ret.append(TextComponent.newline()).append(TextComponent.of(BukkitMessages.template(
                        "flagsCommandHint", "world", world.getName(), "region", regionId)));
            }
            return ret;
        }
    }
}
