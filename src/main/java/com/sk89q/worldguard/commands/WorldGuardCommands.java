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

package com.sk89q.worldguard.commands;

import com.sk89q.worldguard.commands.framework.CommandContext;
import com.sk89q.worldguard.commands.framework.CommandException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.util.task.Task;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitMessages;
import com.sk89q.worldguard.config.ConfigurationManager;

import java.util.List;

public class WorldGuardCommands {

    public WorldGuardCommands(WorldGuard worldGuard) {
    }

    public void reload(CommandContext args, Actor sender) throws CommandException {
        // The supervisor check prevents reload while known asynchronous commands are active.
        List<Task<?>> tasks = WorldGuard.getInstance().getSupervisor().getTasks();
        if (!tasks.isEmpty()) {
            throw new CommandException("@wg:pendingTasks@");
        }
        
        try {
            ConfigurationManager config = WorldGuard.getInstance().getPlatform().getGlobalStateManager();
            config.unload();
            config.load();
            for (World world : WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.GAME_HOOKS).getWorlds()) {
                config.get(world);
            }
            WorldGuard.getInstance().getPlatform().getRegionContainer().reload();
            WorldGuard.getInstance().getPlatform().getRegionContainer().invalidateCache();
            WorldGuard.getInstance().getPlatform().getSessionManager().resetAllStates();
            // WGBukkit.cleanCache();
            sender.print(TextComponent.of("@wg:configurationReloaded@"));
        } catch (Throwable t) {
            sender.printError(TextComponent.of(BukkitMessages.template(
                    "reloadError", "error", String.valueOf(t.getMessage()))));
        }
    }
}
