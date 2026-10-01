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

package com.sk89q.worldguard.util;

import com.sk89q.worldguard.commands.framework.CommandException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.internal.command.exception.ExceptionConverterHelper;
import com.sk89q.worldedit.internal.command.exception.ExceptionMatch;
import com.sk89q.worldedit.util.auth.AuthorizationException;
import com.sk89q.worldedit.util.formatting.component.InvalidComponentException;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitMessages;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.util.UnresolvedNamesException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorldGuardExceptionConverter extends ExceptionConverterHelper {

    private static final Pattern numberFormat = Pattern.compile("^For input string: \"(.*)\"$");

    private CommandException newCommandException(String message, Throwable cause) {
        return new CommandException(message, cause);
    }

    @ExceptionMatch
    public void convert(NumberFormatException e) throws CommandException {
        final Matcher matcher = numberFormat.matcher(e.getMessage());

        if (matcher.matches()) {
            throw newCommandException(BukkitMessages.template(
                    "exceptionNumberExpectedValue", "value", matcher.group(1)), e);
        } else {
            throw newCommandException("@wg:exceptionNumberExpected@", e);
        }
    }

    @ExceptionMatch
    public void convert(InvalidComponentException e) throws CommandException {
        throw newCommandException(BukkitMessages.template(
                "exceptionInvalidComponent", "error", String.valueOf(e.getMessage())), e);
    }

    @ExceptionMatch
    public void convert(StorageException e) throws CommandException {
        WorldGuard.logger.log(Level.WARNING, "@wglog:logErrorLoadingSavingRegions@", e);
        throw newCommandException("@wg:exceptionRegionStorage@", e);
    }

    @ExceptionMatch
    public void convert(RejectedExecutionException e) throws CommandException {
        throw newCommandException("@wg:exceptionTaskQueueFull@", e);
    }

    @ExceptionMatch
    public void convert(CancellationException e) throws CommandException {
        throw newCommandException("@wg:exceptionTaskCancelled@", e);
    }

    @ExceptionMatch
    public void convert(InterruptedException e) throws CommandException {
        throw newCommandException("@wg:exceptionTaskInterrupted@", e);
    }

    @ExceptionMatch
    public void convert(WorldEditException e) throws CommandException {
        throw newCommandException(BukkitMessages.template(
                "exceptionWorldEdit", "error", String.valueOf(e.getMessage())), e);
    }

    @ExceptionMatch
    public void convert(UnresolvedNamesException e) throws CommandException {
        throw newCommandException(BukkitMessages.template(
                "exceptionUnresolvedNames", "error", String.valueOf(e.getMessage())), e);
    }

    @ExceptionMatch
    public void convert(AuthorizationException e) throws CommandException {
        throw newCommandException("@wg:permissionDenied@", e);
    }
}
