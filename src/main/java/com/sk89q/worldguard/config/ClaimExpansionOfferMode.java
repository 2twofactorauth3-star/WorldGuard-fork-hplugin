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

package com.sk89q.worldguard.config;

public enum ClaimExpansionOfferMode {

    DISABLED(1, false, false),
    SELECTION(2, true, false),
    CLAIM(3, false, true),
    BOTH(4, true, true);

    public final int configValue;
    public final boolean selection;
    public final boolean claim;

    ClaimExpansionOfferMode(int configValue, boolean selection, boolean claim) {
        this.configValue = configValue;
        this.selection = selection;
        this.claim = claim;
    }

    public static ClaimExpansionOfferMode fromConfigValue(int value) {
        for (ClaimExpansionOfferMode mode : values()) {
            if (mode.configValue == value) {
                return mode;
            }
        }
        return BOTH;
    }
}
