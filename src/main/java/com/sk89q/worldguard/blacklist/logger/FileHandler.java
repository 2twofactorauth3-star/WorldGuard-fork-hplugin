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

package com.sk89q.worldguard.blacklist.logger;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.formatting.text.serializer.plain.PlainComponentSerializer;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.blacklist.event.BlacklistEvent;
import com.sk89q.worldguard.blacklist.target.Target;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileHandler implements LoggerHandler {

    private static final Pattern PATTERN = Pattern.compile("%.");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final int cacheSize;
    private final String pathPattern;
    private final String worldName;
    private final Map<String, LogFileWriter> writers = new LinkedHashMap<>(16, 0.75f, true);
    
    private final Logger logger;

    /**
     * Construct the object.
     *
     * @param pathPattern The pattern for the log file path
     * @param worldName The name of the world
     * @param logger The logger used to log errors
     */
    public FileHandler(String pathPattern, String worldName, Logger logger) {
        this.pathPattern = pathPattern;
        this.cacheSize = 10;
        this.worldName = worldName;
        this.logger = logger;
    }

    /**
     * Construct the object.
     *
     * @param pathPattern The pattern for logfile paths
     * @param cacheSize The size of the file cache
     * @param worldName The name of the associated world
     * @param logger The logger to log errors with
     */
    public FileHandler(String pathPattern, int cacheSize, String worldName, Logger logger) {
        if (cacheSize < 1) {
            throw new IllegalArgumentException("Cache size cannot be less than 1");
        }
        this.pathPattern = pathPattern;
        this.cacheSize = cacheSize;
        this.worldName = worldName;
        this.logger = logger;
    }

    /**
     * Build the path.
     *
     * @param playerName The name of the player
     * @return The path for the logfile, null when no applicable path could be found
     */
    private @Nullable String buildPath(@Nullable String playerName) {
        LocalDateTime now = LocalDateTime.now();
        Matcher m = PATTERN.matcher(pathPattern);
        StringBuilder buffer = new StringBuilder();

        // Pattern replacements
        while (m.find()) {
            String rep = switch (m.group().charAt(1)) {
                case '%' -> "%";
                case 'u' -> playerName == null ? null : sanitize(playerName);
                case 'w' -> sanitize(worldName);
                case 'Y' -> Integer.toString(now.getYear());
                case 'm' -> twoDigits(now.getMonthValue());
                case 'd' -> twoDigits(now.getDayOfMonth());
                case 'W' -> twoDigits(now.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));
                case 'H' -> twoDigits(now.getHour());
                case 'h' -> twoDigits(now.getHour() % 12);
                case 'i' -> twoDigits(now.getMinute());
                case 's' -> twoDigits(now.getSecond());
                default -> "?";
            };
            if (rep == null) return null;

            m.appendReplacement(buffer, Matcher.quoteReplacement(rep));
        }

        m.appendTail(buffer);

        return buffer.toString();
    }

    private static String sanitize(String value) {
        int length = Math.min(value.length(), 32);
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            char character = Character.toLowerCase(value.charAt(index));
            result.append(character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_' ? character : '_');
        }
        return result.toString();
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    /**
     * Log a message.
     *
     * @param player The player to log
     * @param message The message to log
     * @param comment The comment associated with the logged event
     */
    private synchronized void log(@Nullable LocalPlayer player, String message, String comment) {
        String path = buildPath(player != null ? player.getName() : null);
        if (path == null) return;
        try {
            String date = DATE_FORMAT.format(LocalDateTime.now());
            String line = "[" + date + "] " + (player != null ? player.getName() : "Unknown Source") + ": " + message
                    + (comment != null ? " (" + comment + ")" : "") + "\r\n";

            LogFileWriter writer = writers.get(path);

            // Writer already exists!
            if (writer != null) {
                try {
                    BufferedWriter out = writer.getWriter();
                    out.write(line);
                    out.flush();
                    writer.updateLastUse();
                    return;
                } catch (IOException e) {
                    // Failed initial rewrite... let's re-open
                }
            }

            // Make parent directory
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            FileWriter stream = new FileWriter(path, true);
            BufferedWriter out = new BufferedWriter(stream);
            out.write(line);
            out.flush();
            writer = new LogFileWriter(path, out);
            writers.put(path, writer);

            // Check to make sure our cache doesn't get too big!
            if (writers.size() > cacheSize) {
                Iterator<Map.Entry<String,LogFileWriter>> it =
                        writers.entrySet().iterator();

                // Remove some entries
                while (it.hasNext()) {
                    Map.Entry<String,LogFileWriter> entry = it.next();
                    try {
                        entry.getValue().getWriter().close();
                    } catch (IOException ignore) {
                    }
                    it.remove();

                    // Trimmed enough
                    if (writers.size() <= cacheSize) {
                        break;
                    }
                }
            }

        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to log blacklist event to '"
                    + path + "': " + e.getMessage());
        }
    }

    /**
     * Gets the coordinates in text form for the log.
     *
     * @param pos The position to get coordinates for
     * @return The position's coordinates in human-readable form
     */
    private String getCoordinates(BlockVector3 pos) {
        return "@" + pos.x() + "," + pos.y() + "," + pos.z();
    }

    private void logEvent(BlacklistEvent event, String text, Target target, BlockVector3 pos, String comment) {
        log(event.getPlayer(), "Tried to " + text + " " + PlainComponentSerializer.INSTANCE.serialize(target.getFriendlyNameComponent()) + " " + getCoordinates(pos), comment);
    }

    @Override
    public void logEvent(BlacklistEvent event, String comment) {
        logEvent(event, event.getDescription(), event.getTarget(), event.getPosition(), comment);
    }

    @Override
    public synchronized void close() {
        for (Map.Entry<String,LogFileWriter> entry : writers.entrySet()) {
            try {
                entry.getValue().getWriter().close();
            } catch (IOException ignore) {
            }
        }

        writers.clear();
    }

}
