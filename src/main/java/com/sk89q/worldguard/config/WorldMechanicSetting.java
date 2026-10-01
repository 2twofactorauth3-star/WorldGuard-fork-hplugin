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

/**
 * Controls whether a world mechanic is blocked and where the block applies.
 */
public final class WorldMechanicSetting {

    public final boolean enable;
    public final Mode mode;

    public WorldMechanicSetting(boolean enable, int mode) {
        this.enable = enable;
        this.mode = Mode.fromConfigValue(mode);
    }

    public enum Mode {
        OUTSIDE_REGIONS(1),
        FOREIGN_REGIONS(2),
        EVERYWHERE(3);

        public final int configValue;

        Mode(int configValue) {
            this.configValue = configValue;
        }

        public static boolean isValidConfigValue(int value) {
            for (Mode mode : values()) {
                if (mode.configValue == value) {
                    return true;
                }
            }
            return false;
        }

        private static Mode fromConfigValue(int value) {
            for (Mode mode : values()) {
                if (mode.configValue == value) {
                    return mode;
                }
            }
            return EVERYWHERE;
        }
    }
}
