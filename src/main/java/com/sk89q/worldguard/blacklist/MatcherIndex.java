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

package com.sk89q.worldguard.blacklist;

import com.sk89q.worldguard.blacklist.event.BlacklistEvent;
import com.sk89q.worldguard.blacklist.event.EventType;
import com.sk89q.worldguard.blacklist.target.Target;
import com.sk89q.worldguard.blacklist.target.TargetMatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MatcherIndex {

    private static final MatcherIndex EMPTY_INSTANCE = new MatcherIndex(Map.of(), 0);

    private final Map<String, BlacklistEntry[]> entries;
    private final int size;

    private MatcherIndex(Map<String, BlacklistEntry[]> entries, int size) {
        this.entries = entries;
        this.size = size;
    }

    public boolean check(
            Target target,
            boolean useAsWhitelist,
            BlacklistEvent event,
            boolean forceRepeat,
            boolean silent
    ) {
        BlacklistEntry[] matches = entries.get(target.getTypeId());
        if (matches == null) {
            return !useAsWhitelist;
        }
        boolean allowed = true;
        for (BlacklistEntry entry : matches) {
            if (!entry.check(useAsWhitelist, event, forceRepeat, silent)) {
                allowed = false;
            }
        }
        return allowed;
    }

    public boolean needsCheck(
            Target target,
            Class<? extends BlacklistEvent> eventType,
            boolean useAsWhitelist
    ) {
        EventType type = EventType.fromEventClass(eventType);
        if (type == null) {
            return false;
        }
        return needsCheck(target, type, useAsWhitelist);
    }

    public boolean needsCheck(Target target, EventType eventType, boolean useAsWhitelist) {
        BlacklistEntry[] matches = entries.get(target.getTypeId());
        if (matches == null) {
            return useAsWhitelist;
        }
        for (BlacklistEntry entry : matches) {
            if (useAsWhitelist || entry.hasActions(eventType)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public static MatcherIndex getEmptyInstance() {
        return EMPTY_INSTANCE;
    }

    public static final class Builder {
        private final Map<String, List<BlacklistEntry>> entries = new HashMap<>();
        private int size;

        public Builder add(TargetMatcher matcher, BlacklistEntry entry) {
            entries.computeIfAbsent(matcher.getMatchedTypeId(), ignored -> new ArrayList<>())
                    .add(entry);
            size++;
            return this;
        }

        public MatcherIndex build() {
            if (size == 0) {
                return EMPTY_INSTANCE;
            }
            Map<String, BlacklistEntry[]> built = new HashMap<>(entries.size());
            for (Map.Entry<String, List<BlacklistEntry>> entry : entries.entrySet()) {
                List<BlacklistEntry> value = entry.getValue();
                for (BlacklistEntry blacklistEntry : value) {
                    blacklistEntry.compile();
                }
                built.put(entry.getKey(), value.toArray(BlacklistEntry[]::new));
            }
            return new MatcherIndex(Map.copyOf(built), size);
        }
    }
}
