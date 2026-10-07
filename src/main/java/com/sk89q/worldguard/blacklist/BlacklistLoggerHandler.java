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
import com.sk89q.worldguard.blacklist.logger.LoggerHandler;

import java.util.Arrays;

public class BlacklistLoggerHandler implements LoggerHandler {

    /**
     * List of logger handlers.
     */
    private volatile LoggerHandler[] handlers = new LoggerHandler[0];

    /**
     * Add a handler.
     *
     * @param handler The handler to add
     */
    public synchronized void addHandler(LoggerHandler handler) {
        for (LoggerHandler registered : handlers) {
            if (registered.equals(handler)) return;
        }
        LoggerHandler[] updated = Arrays.copyOf(handlers, handlers.length + 1);
        updated[handlers.length] = handler;
        handlers = updated;
    }

    /**
     * Remove a handler.
     *
     * @param handler The handler to remove
     */
    public synchronized void removeHandler(LoggerHandler handler) {
        for (int index = 0; index < handlers.length; index++) {
            if (handlers[index].equals(handler)) {
                LoggerHandler[] updated = new LoggerHandler[handlers.length - 1];
                System.arraycopy(handlers, 0, updated, 0, index);
                System.arraycopy(handlers, index + 1, updated, index, handlers.length - index - 1);
                handlers = updated;
                return;
            }
        }
    }

    /**
     * Add a handler.
     */
    public synchronized void clearHandlers() {
        handlers = new LoggerHandler[0];
    }

    /**
     * Log an event.
     *
     * @param event The event to log
     */
    @Override
    public void logEvent(BlacklistEvent event, String comment) {
        for (LoggerHandler handler : handlers) {
            handler.logEvent(event, comment);
        }
    }

    /**
     * Close the connection.
     */
    @Override
    public void close() {
        for (LoggerHandler handler : handlers) {
            handler.close();
        }
    }

}
