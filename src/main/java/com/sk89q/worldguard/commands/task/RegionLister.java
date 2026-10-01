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

package com.sk89q.worldguard.commands.task;

import static com.google.common.base.Preconditions.checkNotNull;

import com.sk89q.worldguard.commands.framework.CommandException;
import org.enginehub.squirrelid.Profile;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.formatting.component.PaginationBox;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitMessages;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.internal.permission.RegionPermissionModel;
import com.sk89q.worldguard.protection.FlagValueCalculator;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RegionLister implements Callable<Integer> {

    private static final Logger log = Logger.getLogger(RegionLister.class.getCanonicalName());

    private final Actor sender;
    private final RegionManager manager;
    private final String world;
    private OwnerMatcher ownerMatcher;
    private String idFilter;
    private ProtectedRegion filterByIntersecting;
    private int page;
    private String playerName;
    private boolean nameOnly;
    private String commandScope;

    public RegionLister(RegionManager manager, Actor sender, String world) {
        checkNotNull(manager);
        checkNotNull(sender);
        checkNotNull(world);

        this.manager = manager;
        this.sender = sender;
        this.world = world;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public void setCommandScope(String commandScope) {
        this.commandScope = commandScope;
    }

    public void filterByIntersecting(ProtectedRegion region) {
        this.filterByIntersecting = region;
    }

    public void filterOwnedByName(String name) {
        filterOwnedByName(name, false);
    }

    public void filterOwnedByName(String name, boolean nameOnly) {
        this.playerName = name;
        this.nameOnly = nameOnly;
        if (nameOnly) {
            filterOwnedByNameOnly(name);
        } else {
            filterOwnedByProfile(name);
        }
    }

    private void filterOwnedByNameOnly(final String name) {
        ownerMatcher = new OwnerMatcher() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean isContainedWithin(DefaultDomain domain) {
                return WorldGuard.getInstance().getProfileCache().getAllPresent(domain.getUniqueIds())
                        .values().stream()
                        .anyMatch(profile -> profile.getName().equalsIgnoreCase(name));
            }
        };
    }

    private void filterOwnedByProfile(final String name) {
        ownerMatcher = new OwnerMatcher() {
            private UUID uniqueId;

            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean isContainedWithin(DefaultDomain domain) throws CommandException {
                if (uniqueId == null) {
                    Profile profile;

                    try {
                        profile = WorldGuard.getInstance().getProfileService().findByName(name);
                    } catch (IOException e) {
                        log.log(Level.WARNING, "@wglog:logFailedUuidLookupOf@" + name + "@wglog:logText@", e);
                        throw new CommandException(BukkitMessages.template(
                                "regionLookupUuidFailed", "player", name));
                    } catch (InterruptedException e) {
                        log.log(Level.WARNING, "@wglog:logFailedUuidLookupOf@" + name + "@wglog:logText@", e);
                        throw new CommandException(BukkitMessages.template(
                                "regionLookupInterrupted", "player", name));
                    }

                    if (profile == null) {
                        throw new CommandException(BukkitMessages.template(
                                "userDoesNotExist", "player", name));
                    }

                    uniqueId = profile.getUniqueId();
                }

                return domain.contains(uniqueId);
            }
        };
    }

    public void filterIdByMatch(String idFilter) {
        this.idFilter = idFilter;
    }

    @Override
    public Integer call() throws Exception {
        Map<String, ProtectedRegion> regions = manager.getRegions();
        Collection<ProtectedRegion> iterableRegions = regions.values();
        if (filterByIntersecting != null) {
            iterableRegions = filterByIntersecting.getIntersectingRegions(iterableRegions);
        }
        List<RegionListEntry> entries = collectEntries(iterableRegions);
        Collections.sort(entries);
        addGlobalEntry(regions, entries);
        if (ownerMatcher != null) Collections.sort(entries);

        RegionPermissionModel perms = sender.isPlayer() ? new RegionPermissionModel(sender) : null;
        String title = ownerMatcher == null
                ? "@wg:regionListTitle@"
                : BukkitMessages.template("regionListTitleFor", "owner", ownerMatcher.getName());
        PaginationBox box = new RegionListBox(title, pageCommand(), perms, entries, world);
        sender.print(box.create(page));
        return page;
    }

    private List<RegionListEntry> collectEntries(
            Collection<ProtectedRegion> regions) throws CommandException {
        List<RegionListEntry> entries = new ArrayList<>();
        for (ProtectedRegion region : regions) {
            if (region.getId().equals(ProtectedRegion.GLOBAL_REGION)) continue;
            RegionListEntry entry = new RegionListEntry(region);
            if (entry.matches(idFilter) && entry.matches(ownerMatcher)) entries.add(entry);
        }
        return entries;
    }

    private void addGlobalEntry(
            Map<String, ProtectedRegion> regions,
            List<RegionListEntry> entries) throws CommandException {
        ProtectedRegion global = regions.get(ProtectedRegion.GLOBAL_REGION);
        if (global == null) return;
        RegionListEntry entry = new RegionListEntry(global);
        if (entry.matches(idFilter) && entry.matches(ownerMatcher)) entries.add(0, entry);
    }

    private String pageCommand() {
        boolean implicitMyFilter = "my".equals(commandScope)
                && playerName != null && playerName.equalsIgnoreCase(sender.getName()) && !nameOnly;
        return "/rg list -w \"" + world + "\""
                + (commandScope != null ? " " + commandScope : "")
                + (!implicitMyFilter && playerName != null ? " -p " + playerName : "")
                + (nameOnly ? " -n" : "")
                + (filterByIntersecting != null ? " -s" : "")
                + (idFilter != null ? " -i " + idFilter : "")
                + " %page%";
    }

    private interface OwnerMatcher {
        String getName();

        boolean isContainedWithin(DefaultDomain domain) throws CommandException;
    }

    private static final class RegionListEntry implements Comparable<RegionListEntry> {
        private final ProtectedRegion region;
        private boolean isOwner;
        private boolean isMember;

        private RegionListEntry(ProtectedRegion rg) {
            this.region = rg;
        }

        public boolean matches(OwnerMatcher matcher) throws CommandException {
            return matcher == null
                    || (isOwner = matcher.isContainedWithin(region.getOwners()))
                    || (isMember = matcher.isContainedWithin(region.getMembers()));
        }

        public boolean matches(String idMatcher) {
            return idMatcher == null || region.getId().contains(idMatcher);
        }

        public ProtectedRegion getRegion() {
            return region;
        }

        public boolean isOwner() {
            return isOwner;
        }

        public boolean isMember() {
            return isMember;
        }

        @Override
        public int compareTo(RegionListEntry o) {
            if (isOwner != o.isOwner) {
                return isOwner ? -1 : 1;
            }
            if (isMember != o.isMember) {
                return isMember ? -1 : 1;
            }
            return region.getId().compareTo(o.region.getId());
        }
    }

    private static class RegionListBox extends PaginationBox {
        private final RegionPermissionModel perms;
        private final List<RegionListEntry> entries;
        private String world;

        RegionListBox(String title, String cmd, RegionPermissionModel perms, List<RegionListEntry> entries, String world) {
            super(title, cmd);
            this.perms = perms;
            this.entries = entries;
            this.world = world;
        }

        @Override
        public Component getComponent(int number) {
            final RegionListEntry entry = entries.get(number);
            String entryKey = entry.isOwner()
                    ? "regionListEntryOwner"
                    : entry.isMember() ? "regionListEntryMember" : "regionListEntry";
            StringBuilder message = new StringBuilder(BukkitMessages.template(
                    entryKey, "number", number + 1, "region", entry.getRegion().getId()));
            if (perms != null && perms.mayLookup(entry.region)) {
                message.append(BukkitMessages.template(
                        "buttonInfo", "world", world, "region", entry.region.getId()));
            }
            final Location teleFlag = FlagValueCalculator.getEffectiveFlagOf(entry.region, Flags.TELE_LOC, perms != null && perms.getSender() instanceof RegionAssociable ? (RegionAssociable) perms.getSender() : null);
            if (perms != null && teleFlag != null && perms.mayTeleportTo(entry.region)) {
                message.append(BukkitMessages.template(
                        "buttonTeleport",
                        "world", world,
                        "region", entry.region.getId(),
                        "x", teleFlag.getBlockX(),
                        "y", teleFlag.getBlockY(),
                        "z", teleFlag.getBlockZ()));
            } else if (perms != null && perms.mayTeleportToCenter(entry.getRegion()) && entry.getRegion().isPhysicalArea()) {
                message.append(BukkitMessages.template(
                        "buttonTeleportCenter", "world", world, "region", entry.region.getId()));
            }
            return TextComponent.of(message.toString());
        }

        @Override
        public int getComponentsSize() {
            return entries.size();
        }
    }
}
