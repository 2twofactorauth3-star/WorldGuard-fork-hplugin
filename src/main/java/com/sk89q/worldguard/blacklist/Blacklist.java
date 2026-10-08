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

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.blacklist.action.Action;
import com.sk89q.worldguard.blacklist.action.ActionType;
import com.sk89q.worldguard.blacklist.event.BlacklistEvent;
import com.sk89q.worldguard.blacklist.event.EventType;
import com.sk89q.worldguard.blacklist.target.Target;
import com.sk89q.worldguard.blacklist.target.TargetMatcher;
import com.sk89q.worldguard.blacklist.target.TargetMatcherParseException;
import com.sk89q.worldguard.blacklist.target.TargetMatcherParser;
import com.sk89q.worldguard.commands.CommandUtils;
import com.sk89q.worldguard.util.formatting.component.BlacklistNotify;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Blacklist {

    private static final Logger log = Logger.getLogger(Blacklist.class.getCanonicalName());

    private MatcherIndex index = MatcherIndex.getEmptyInstance();
    private final BlacklistLoggerHandler blacklistLogger = new BlacklistLoggerHandler();
    private volatile BlacklistEvent lastEvent;
    private final boolean useAsWhitelist;
    private final LoadingCache<String, TrackedEvent> repeatingEventCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(30, TimeUnit.SECONDS)
            .build(CacheLoader.from(TrackedEvent::new));

    public Blacklist(boolean useAsWhitelist) {
        this.useAsWhitelist = useAsWhitelist;
    }

    /**
     * Returns whether the list is empty.
     *
     * @return whether the blacklist is empty
     */
    public boolean isEmpty() {
        return index.isEmpty();
    }

    /**
     * Get the number of individual items that have blacklist entries.
     *
     * @return The number of items in the blacklist
     */
    public int getItemCount() {
        return index.size();
    }

    /**
     * Returns whether the blacklist is used as a whitelist.
     *
     * @return whether the blacklist is be used as a whitelist
     */
    public boolean isWhitelist() {
        return useAsWhitelist;
    }

    /**
     * Get the log.
     *
     * @return The logger used in this blacklist
     */
    public BlacklistLoggerHandler getLogger() {
        return blacklistLogger;
    }

    /**
     * Method to handle the event.
     *
     * @param event The event to check
     * @param forceRepeat Whether to force quickly repeating notifications
     * @param silent Whether to force-deny notifications
     * @return Whether the event is allowed
     */
    public boolean check(BlacklistEvent event, boolean forceRepeat, boolean silent) {
        return index.check(event.getTarget(), useAsWhitelist, event, forceRepeat, silent);
    }

    public boolean needsCheck(Target target, Class<? extends BlacklistEvent> eventType) {
        return index.needsCheck(target, eventType, useAsWhitelist);
    }

    public boolean needsCheck(Target target, EventType eventType) {
        return index.needsCheck(target, eventType, useAsWhitelist);
    }

    /**
     * Load the blacklist.
     *
     * @param file The file to load from
     * @throws IOException if an error occurred reading from the file
     */
    public void load(File file) throws IOException {
        MatcherIndex.Builder builder = new MatcherIndex.Builder();
        TargetMatcherParser targetMatcherParser = new TargetMatcherParser();
        try (BufferedReader buff = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            List<BlacklistEntry> currentEntries = null;
            while ((line = buff.readLine()) != null) {
                line = line.trim();
                if (isIgnoredLine(line)) continue;
                if (isHeading(line)) {
                    currentEntries = parseHeading(line, builder, targetMatcherParser);
                    continue;
                }
                if (currentEntries == null) {
                    log.log(Level.WARNING, "Found option with no heading "
                            + file.getName() + " for '" + line + "'");
                    continue;
                }
                applyOption(file, line, currentEntries);
            }
            this.index = builder.build();
        }
    }

    private static boolean isIgnoredLine(String line) {
        return line.isEmpty() || line.charAt(0) == ';' || line.charAt(0) == '#';
    }

    private static boolean isHeading(String line) {
        return line.length() >= 2 && line.charAt(0) == '[' && line.charAt(line.length() - 1) == ']';
    }

    private List<BlacklistEntry> parseHeading(String line, MatcherIndex.Builder builder,
                                              TargetMatcherParser targetMatcherParser) {
        String[] items = line.substring(1, line.length() - 1).split(",");
        List<BlacklistEntry> entries = new ArrayList<>(items.length);
        for (String item : items) {
            try {
                TargetMatcher matcher = targetMatcherParser.fromInput(item.trim());
                BlacklistEntry entry = new BlacklistEntry(this);
                builder.add(matcher, entry);
                entries.add(entry);
            } catch (TargetMatcherParseException e) {
                log.log(Level.WARNING, "Could not parse a block/item heading: " + e.getMessage());
            }
        }
        return entries;
    }

    private void applyOption(File file, String line, List<BlacklistEntry> entries) {
        int separator = line.indexOf('=');
        if (separator < 0) {
            log.log(Level.WARNING, "Found option with no value " + file.getName() + " for '" + line + "'");
            return;
        }
        String option = line.substring(0, separator).trim();
        String value = line.substring(separator + 1);
        EventType eventType = EventType.fromRuleName(option);
        if (applyEntryOption(entries, option, value, eventType)) return;
        log.log(Level.WARNING, "Unknown option '" + option + "' in " + file.getName() + " for '" + line + "'");
    }

    private boolean applyEntryOption(List<BlacklistEntry> entries, String option, String value,
                                     EventType eventType) {
        if (eventType != null) {
            for (BlacklistEntry entry : entries) {
                entry.getActions(eventType.eventClass).addAll(parseActions(entry, value));
            }
            return true;
        }
        if (option.equalsIgnoreCase("ignore-groups")) {
            setIgnoreGroups(entries, value.split(","));
        } else if (option.equalsIgnoreCase("ignore-perms")) {
            setIgnorePermissions(entries, value.split(","));
        } else if (option.equalsIgnoreCase("message")) {
            setMessage(entries, CommandUtils.replaceColorMacros(value.trim()));
        } else if (option.equalsIgnoreCase("comment")) {
            setComment(entries, CommandUtils.replaceColorMacros(value.trim()));
        } else {
            return false;
        }
        return true;
    }

    private static void setIgnoreGroups(List<BlacklistEntry> entries, String[] groups) {
        for (BlacklistEntry entry : entries) entry.setIgnoreGroups(groups);
    }

    private static void setIgnorePermissions(List<BlacklistEntry> entries, String[] permissions) {
        for (BlacklistEntry entry : entries) entry.setIgnorePermissions(permissions);
    }

    private static void setMessage(List<BlacklistEntry> entries, String message) {
        for (BlacklistEntry entry : entries) entry.setMessage(message);
    }

    private static void setComment(List<BlacklistEntry> entries, String comment) {
        for (BlacklistEntry entry : entries) entry.setComment(comment);
    }

    private List<Action> parseActions(BlacklistEntry entry, String raw) {
        String[] split = raw.split(",");
        List<Action> actions = new ArrayList<>();

        for (String name : split) {
            name = name.trim();

            boolean found = false;

            for (ActionType type : ActionType.values()) {
                if (type.getActionName().equalsIgnoreCase(name)) {
                    actions.add(type.parseInput(this, entry));
                    found = true;
                    break;
                }
            }

            if (!found) {
                log.log(Level.WARNING, "Unknown blacklist action: " + name);
            }
        }

        return actions;
    }

    /**
     * Get the last event.
     *
     * @return The last event
     */
    public BlacklistEvent getLastEvent() {
        return lastEvent;
    }

    /**
     * Notify administrators.
     *
     * @param event The event to notify about
     * @param comment The comment to notify with
     */
    public void notify(BlacklistEvent event, String comment) {
        lastEvent = event;

        WorldGuard.getInstance().getPlatform().broadcastNotification(new BlacklistNotify(event, comment).create());
    }

    public LoadingCache<String, TrackedEvent> getRepeatingEventCache() {
        return repeatingEventCache;
    }

}
