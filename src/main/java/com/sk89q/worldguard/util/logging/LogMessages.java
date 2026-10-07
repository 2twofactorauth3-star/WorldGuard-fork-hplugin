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

package com.sk89q.worldguard.util.logging;

import java.util.function.Function;

/** Resolves opaque WorldGuard log keys through the platform locale. */
public final class LogMessages {

    private static volatile Function<String, String> resolver = Function.identity();

    private LogMessages() {
    }

    public static void setResolver(Function<String, String> resolver) {
        LogMessages.resolver = resolver;
    }

    public static String resolve(String message) {
        if (message == null || message.isEmpty()) return "";
        return resolver.apply(message);
    }
}
