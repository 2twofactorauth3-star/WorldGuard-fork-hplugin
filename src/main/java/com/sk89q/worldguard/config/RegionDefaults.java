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

package com.sk89q.worldguard.config;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion.CircularInheritanceException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Defaults applied to newly created regions or the main global region. */
public final class RegionDefaults {

    public final int priority;
    public final String parent;
    public final Map<String, Object> flags;
    public final List<String> clearFlags;
    public final List<String> ownerPlayers;
    public final List<String> ownerGroups;
    public final List<String> memberPlayers;
    public final List<String> memberGroups;

    public RegionDefaults(int priority, String parent, Map<String, Object> flags, List<String> clearFlags,
                          List<String> ownerPlayers, List<String> ownerGroups,
                          List<String> memberPlayers, List<String> memberGroups) {
        this.priority = priority;
        this.parent = parent == null ? "" : parent.trim();
        this.flags = Map.copyOf(flags);
        this.clearFlags = List.copyOf(clearFlags);
        this.ownerPlayers = List.copyOf(ownerPlayers);
        this.ownerGroups = List.copyOf(ownerGroups);
        this.memberPlayers = List.copyOf(memberPlayers);
        this.memberGroups = List.copyOf(memberGroups);
    }

    public void applyToNewRegion(ProtectedRegion region, RegionManager manager) {
        applyPriorityAndFlags(region);
        applyParent(region, manager);
        region.setOwners(createDomain(ownerPlayers, ownerGroups));
        region.setMembers(createDomain(memberPlayers, memberGroups));
    }

    public void applyToGlobalRegion(ProtectedRegion region) {
        applyPriorityAndFlags(region);
    }

    public void applyToExistingGlobalRegion(ProtectedRegion region) {
        applyMissingFlags(region);
    }

    public void applyToExistingRegion(ProtectedRegion region, RegionManager manager) {
        applyMissingFlags(region);
        if (region.getParent() == null) {
            applyParent(region, manager);
        }
    }

    private void applyPriorityAndFlags(ProtectedRegion region) {
        region.setPriority(priority);
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        region.setFlags(parseFlags(registry));
    }

    private void applyMissingFlags(ProtectedRegion region) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        Map<Flag<?>, Object> mergedFlags = new LinkedHashMap<>(region.getFlags());
        for (String name : clearFlags) {
            Flag<?> flag = findFlag(registry, name);
            if (flag == null) {
                WorldConfiguration.log.warning(
                        "@wglog:regionDefaultsUnknownFlag@" + name + "@wglog:logText@");
            } else {
                mergedFlags.remove(flag);
            }
        }
        parseFlags(registry).forEach(mergedFlags::putIfAbsent);
        region.setFlags(mergedFlags);
    }

    private Map<Flag<?>, Object> parseFlags(FlagRegistry registry) {
        Map<Flag<?>, Object> parsedFlags = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : flags.entrySet()) {
            String name = entry.getKey();
            String baseName = name.endsWith("-group")
                    ? name.substring(0, name.length() - "-group".length())
                    : name;
            Flag<?> flag = registry.get(baseName);
            if (flag == null) {
                WorldConfiguration.log.warning(
                        "@wglog:regionDefaultsUnknownFlag@" + name + "@wglog:logText@");
                continue;
            }
            try {
                parsedFlags.putAll(registry.unmarshal(Map.of(name, entry.getValue()), false));
            } catch (RuntimeException e) {
                WorldConfiguration.log.warning(
                        "@wglog:regionDefaultsInvalidFlag@" + name + "@wglog:logText@");
            }
        }
        return parsedFlags;
    }

    private static Flag<?> findFlag(FlagRegistry registry, String name) {
        if (name.endsWith("-group")) {
            Flag<?> parentFlag = registry.get(name.substring(0, name.length() - "-group".length()));
            return parentFlag == null ? null : parentFlag.getRegionGroupFlag();
        }
        return registry.get(name);
    }

    private void applyParent(ProtectedRegion region, RegionManager manager) {
        if (parent.isEmpty()) {
            return;
        }
        ProtectedRegion parentRegion = manager.getRegion(parent);
        if (parentRegion == null) {
            WorldConfiguration.log.warning(
                    "@wglog:regionDefaultsMissingParent@" + parent + "@wglog:logText@");
            return;
        }
        try {
            region.setParent(parentRegion);
        } catch (CircularInheritanceException e) {
            WorldConfiguration.log.warning(
                    "@wglog:regionDefaultsInvalidParent@" + parent + "@wglog:logText@");
        }
    }

    private static DefaultDomain createDomain(List<String> players, List<String> groups) {
        DefaultDomain domain = new DefaultDomain();
        for (String player : players) {
            try {
                domain.addPlayer(UUID.fromString(player));
            } catch (IllegalArgumentException ignored) {
                WorldConfiguration.log.warning(
                        "@wglog:regionDefaultsInvalidPlayerUuid@" + player + "@wglog:logText@");
            }
        }
        for (String group : groups) {
            domain.addGroup(group);
        }
        return domain;
    }
}
