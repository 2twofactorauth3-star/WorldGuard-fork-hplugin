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

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.blacklist.action.Action;
import com.sk89q.worldguard.blacklist.action.ActionResult;
import com.sk89q.worldguard.blacklist.action.RepeatGuardedAction;
import com.sk89q.worldguard.blacklist.event.BlacklistEvent;
import com.sk89q.worldguard.blacklist.event.EventType;
import com.google.common.cache.LoadingCache;

import javax.annotation.Nullable;
import java.util.*;

public class BlacklistEntry {

    private static final Action[] NO_ACTIONS = new Action[0];

    private final Blacklist blacklist;
    private Set<String> ignoreGroups;
    private Set<String> ignorePermissions;
    private final Map<Class<? extends BlacklistEvent>, List<Action>> actions = new HashMap<>();
    private Action[][] compiledActions;
    private boolean[] repeatingActions;

    private String message;
    private String comment;

    /**
     * Construct the object.
     *
     * @param blacklist The blacklist that contains this entry
     */
    public BlacklistEntry(Blacklist blacklist) {
        this.blacklist = blacklist;
    }

    /**
     * @return the ignoreGroups
     */
    public String[] getIgnoreGroups() {
        return ignoreGroups == null ? new String[0] : ignoreGroups.toArray(String[]::new);
    }

    /**
     * @return the ignoreGroups
     */
    public String[] getIgnorePermissions() {
        return ignorePermissions == null ? new String[0] : ignorePermissions.toArray(String[]::new);
    }

    /**
     * @param ignoreGroups the ignoreGroups to set
     */
    public void setIgnoreGroups(String[] ignoreGroups) {
        Set<String> ignoreGroupsSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String group : ignoreGroups) {
            ignoreGroupsSet.add(group);
        }
        this.ignoreGroups = ignoreGroupsSet;
    }

    /**
     * @param ignorePermissions the ignorePermissions to set
     */
    public void setIgnorePermissions(String[] ignorePermissions) {
        Set<String> ignorePermissionsSet = new HashSet<>(ignorePermissions.length);
        Collections.addAll(ignorePermissionsSet, ignorePermissions);
        this.ignorePermissions = ignorePermissionsSet;
    }

    /**
     * @return the message
     */
    public String getMessage() {
        return message;
    }

    /**
     * @param message the message to set
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * @return the comment
     */
    public String getComment() {
        return comment;
    }

    /**
     * @param comment the comment to set
     */
    public void setComment(String comment) {
        this.comment = comment;
    }

    /**
     * Returns true if this player should be ignored.
     *
     * @param player The player to check
     * @return whether this player should be ignored for blacklist blocking
     */
    public boolean shouldIgnore(@Nullable LocalPlayer player) {
        if (player == null) {
            return false; // This is the case if the cause is a dispenser, for example
        }

        if (ignoreGroups != null) {
            for (String group : player.getGroups()) {
                if (ignoreGroups.contains(group)) {
                    return true;
                }
            }
        }

        if (ignorePermissions != null) {
            for (String perm : ignorePermissions) {
                if (player.hasPermission(perm)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Get the associated actions with an event.
     *
     * @param eventCls The event's class
     * @return The actions for the given event
     */
    public List<Action> getActions(Class<? extends BlacklistEvent> eventCls) {
        return actions.computeIfAbsent(eventCls, k -> new ArrayList<>());
    }

    void compile() {
        EventType[] eventTypes = EventType.values();
        Action[][] compiled = new Action[eventTypes.length][];
        boolean[] repeating = new boolean[eventTypes.length];
        for (EventType eventType : eventTypes) {
            List<Action> eventActions = actions.get(eventType.eventClass);
            if (eventActions == null || eventActions.isEmpty()) {
                compiled[eventType.ordinal()] = NO_ACTIONS;
                continue;
            }
            Action[] actionArray = eventActions.toArray(Action[]::new);
            compiled[eventType.ordinal()] = actionArray;
            for (Action action : actionArray) {
                if (action instanceof RepeatGuardedAction) {
                    repeating[eventType.ordinal()] = true;
                    break;
                }
            }
        }
        compiledActions = compiled;
        repeatingActions = repeating;
    }

    boolean hasActions(EventType eventType) {
        Action[][] compiled = compiledActions;
        if (compiled != null) {
            return compiled[eventType.ordinal()].length != 0;
        }
        List<Action> eventActions = actions.get(eventType.eventClass);
        return eventActions != null && !eventActions.isEmpty();
    }

    /**
     * Method to handle the event.
     *
     * @param useAsWhitelist Whether this entry is being used in a whitelist
     * @param event The event to check
     * @param forceRepeat Whether to force repeating notifications even within the delay limit
     * @param silent Whether to prevent notifications from happening
     * @return Whether the action was allowed
     */
    public boolean check(boolean useAsWhitelist, BlacklistEvent event, boolean forceRepeat, boolean silent) {
        EventType eventType = event.getEventType();
        Action[] eventActions = compiledActions[eventType.ordinal()];
        boolean ret = !useAsWhitelist;
        if (eventActions.length == 0) {
            return ret;
        }

        LocalPlayer player = event.getPlayer();

        if (shouldIgnore(player)) {
            return true;
        }

        boolean repeating = false;
        if (repeatingActions[eventType.ordinal()]) {
            String eventCacheKey = event.getCauseName();
            LoadingCache<String, TrackedEvent> repeatingEventCache = blacklist.getRepeatingEventCache();
            TrackedEvent tracked = repeatingEventCache.getUnchecked(eventCacheKey);
            if (tracked.matches(event)) {
                repeating = true;
            } else {
                tracked.setLastEvent(event);
                tracked.resetTimer();
            }
        }

        for (Action action : eventActions) {
            ActionResult result = action.apply(event, silent, repeating, forceRepeat);
            switch (result) {
                case INHERIT:
                    continue;
                case ALLOW:
                    ret = true;
                    break;
                case DENY:
                    ret = false;
                    break;
                case ALLOW_OVERRIDE:
                    return true;
                case DENY_OVERRIDE:
                    return false;
            }
        }

        return ret;
    }

}
