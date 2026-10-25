package com.sk89q.worldguard.bukkit.integration;

import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.commands.CommandsHandler;
import com.Zrips.CMI.commands.list.god;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class CmiGodApi implements CmiIntegration.GodApi {

    private final CommandsHandler commands;
    private final CommandSender sender;

    private CmiGodApi(CommandsHandler commands, CommandSender sender) {
        this.commands = commands;
        this.sender = sender;
    }

    static CmiIntegration.GodApi create(Plugin dependency, CommandSender sender) {
        if (!(dependency instanceof CMI cmi)) {
            throw new IllegalStateException("CMI plugin does not implement its API");
        }
        CommandsHandler commands = cmi.getCommandManager();
        if (commands == null) throw new IllegalStateException("CMI command API is not available");
        return new CmiGodApi(commands, sender);
    }

    @Override
    public CmiIntegration.GodState capture(Player player) {
        return new CmiIntegration.GodState(player.isInvulnerable(), user(player).getTgod());
    }

    @Override
    public void apply(Player player, boolean value) {
        CMIUser user = user(player);
        if (!value && user.getTgod() != 0) {
            user.setTgod(0L, false);
        }
        setPermanentGod(player, value);
    }

    @Override
    public void restore(Player player, CmiIntegration.GodState state) {
        CMIUser user = user(player);
        setPermanentGod(player, state.invulnerable);
        if (user.getTgod() != state.temporaryGod) {
            user.setTgod(state.temporaryGod, false);
        }
    }

    private void setPermanentGod(Player player, boolean value) {
        if (player.isInvulnerable() == value) return;
        Boolean success = commands.performCMICommand(sender, god.class, player.getName(), Boolean.toString(value), "-s");
        if (Boolean.FALSE.equals(success)) throw new IllegalStateException("CMI could not change god mode");
    }

    private static CMIUser user(Player player) {
        CMIUser user = CMIUser.getUser(player);
        if (user == null) throw new IllegalStateException("CMI player data is not available");
        return user;
    }
}
