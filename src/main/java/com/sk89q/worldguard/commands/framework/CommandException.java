/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) WorldGuard team and contributors
 */

package com.sk89q.worldguard.commands.framework;

public class CommandException extends Exception {
    public CommandException(String message) {
        super(message);
    }

    public CommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
