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

package com.sk89q.worldguard.domains;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.util.ChangeTracked;
import org.enginehub.squirrelid.Profile;
import org.enginehub.squirrelid.cache.ProfileCache;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Stores players (only) in a domain.
 */
public class PlayerDomain implements Domain, ChangeTracked {

    private final Set<UUID> uniqueIds = new CopyOnWriteArraySet<>();
    private boolean dirty = true;

    /**
     * Create a new instance.
     */
    public PlayerDomain() {
    }

    /**
     * Create a new instance.
     *
     * @param domain the domain to copy values from
     */
    public PlayerDomain(PlayerDomain domain) {
        checkNotNull(domain, "domain");
        uniqueIds.addAll(domain.getUniqueIds());
        dirty = true;
    }

    /**
     * Add the given player to the domain, identified by the player's UUID.
     *
     * @param uniqueId the UUID of the player
     */
    public void addPlayer(UUID uniqueId) {
        checkNotNull(uniqueId);
        setDirty(true);
        uniqueIds.add(uniqueId);
    }

    /**
     * Add the given player to the domain, identified by the player's UUID.
     *
     * @param player the player
     */
    public void addPlayer(LocalPlayer player) {
        checkNotNull(player);
        setDirty(true);
        addPlayer(player.getUniqueId());
    }

    /**
     * Remove the given player from the domain, identified by the player's UUID.
     *
     * @param uuid the UUID of the player
     */
    public void removePlayer(UUID uuid) {
        checkNotNull(uuid);
        setDirty(true);
        uniqueIds.remove(uuid);
    }

    /**
     * Remove the given player from the domain by UUID.
     *
     * @param player the player
     */
    public void removePlayer(LocalPlayer player) {
        checkNotNull(player);
        setDirty(true);
        removePlayer(player.getUniqueId());
    }

    @Override
    public boolean contains(LocalPlayer player) {
        checkNotNull(player);
        return contains(player.getUniqueId());
    }

    /**
     * Get the set of player UUIDs.
     *
     * @return the set of player UUIDs
     */
    public Set<UUID> getUniqueIds() {
        return Collections.unmodifiableSet(uniqueIds);
    }

    @Override
    public boolean contains(UUID uniqueId) {
        checkNotNull(uniqueId);
        return uniqueIds.contains(uniqueId);
    }

    @Override
    public boolean contains(String playerName) {
        checkNotNull(playerName);
        String identifier = playerName.startsWith("uuid:")
                ? playerName.substring("uuid:".length()) : playerName;
        try {
            return contains(UUID.fromString(identifier));
        } catch (IllegalArgumentException ignored) {
            // Continue with a case-insensitive lookup in the local profile cache.
        }

        ProfileCache cache = WorldGuard.getInstance().getProfileCache();
        if (cache == null) {
            return false;
        }
        for (UUID uniqueId : uniqueIds) {
            Profile profile = cache.getIfPresent(uniqueId);
            if (profile != null && profile.getName() != null
                    && profile.getName().equalsIgnoreCase(playerName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int size() {
        return uniqueIds.size();
    }

    @Override
    public void clear() {
        setDirty(true);
        uniqueIds.clear();
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    @Override
    public String toString() {
        return "{" +
                "uuids=" + uniqueIds +
                '}';
    }
}
