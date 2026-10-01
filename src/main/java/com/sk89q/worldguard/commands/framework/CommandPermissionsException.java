/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) WorldGuard team and contributors
 */

package com.sk89q.worldguard.commands.framework;

public final class CommandPermissionsException extends CommandException {
    public CommandPermissionsException() {
        super("@wg:permissionDenied@");
    }
}
