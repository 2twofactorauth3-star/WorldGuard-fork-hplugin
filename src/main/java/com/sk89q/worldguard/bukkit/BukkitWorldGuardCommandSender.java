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

package com.sk89q.worldguard.bukkit;

import com.sk89q.worldedit.bukkit.BukkitCommandSender;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.util.formatting.text.Component;
import org.bukkit.command.CommandSender;

@SuppressWarnings("deprecation")
final class BukkitWorldGuardCommandSender extends BukkitCommandSender {

    private final BukkitMessages messages;
    private final CommandSender sender;

    BukkitWorldGuardCommandSender(WorldEditPlugin worldEdit, CommandSender sender, BukkitMessages messages) {
        super(worldEdit, sender);
        this.messages = messages;
        this.sender = sender;
    }

    @Override
    public void printRaw(String message) {
        messages.send(sender, message);
    }

    @Override
    public void print(String message) {
        messages.send(sender, message);
    }

    @Override
    public void printDebug(String message) {
        messages.send(sender, message);
    }

    @Override
    public void printError(String message) {
        messages.send(sender, message);
    }

    @Override
    public void print(Component component) {
        messages.send(sender, component);
    }
}
