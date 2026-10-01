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

import com.google.common.collect.ImmutableList;
import com.sk89q.worldguard.commands.framework.CommandException;
import com.sk89q.worldguard.commands.framework.CommandPermissionsException;
import com.sk89q.wepif.PermissionsResolverManager;
import com.sk89q.worldedit.bukkit.BukkitCommandSender;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.event.player.ProcessPlayerEvent;
import com.sk89q.worldguard.bukkit.listener.EventAbstractionListener;
import com.sk89q.worldguard.bukkit.listener.InvincibilityListener;
import com.sk89q.worldguard.bukkit.listener.PlayerMoveListener;
import com.sk89q.worldguard.bukkit.listener.RegionFlagsListener;
import com.sk89q.worldguard.bukkit.listener.RegionProtectionListener;
import com.sk89q.worldguard.bukkit.listener.WorldGuardBlockListener;
import com.sk89q.worldguard.bukkit.listener.WorldGuardEntityListener;
import com.sk89q.worldguard.bukkit.listener.WorldGuardHangingListener;
import com.sk89q.worldguard.bukkit.listener.WorldGuardPlayerListener;
import com.sk89q.worldguard.bukkit.listener.WorldGuardVehicleListener;
import com.sk89q.worldguard.bukkit.listener.WorldGuardWeatherListener;
import com.sk89q.worldguard.bukkit.listener.WorldGuardWorldListener;
import com.sk89q.worldguard.bukkit.session.BukkitSessionManager;
import com.sk89q.worldguard.bukkit.util.ClassSourceValidator;
import com.sk89q.worldguard.bukkit.util.Entities;
import com.sk89q.worldguard.bukkit.util.Events;
import com.sk89q.worldguard.bukkit.util.MMSupport;
import com.sk89q.worldguard.bukkit.util.SelectionVisualizer;
import com.sk89q.worldguard.commands.WorldGuardCommands;
import com.sk89q.worldguard.bukkit.commands.PaperCommandDispatcher;
import com.sk89q.worldguard.commands.region.MemberCommands;
import com.sk89q.worldguard.commands.region.RegionCommands;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.SimpleFlagRegistry;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.util.logging.LogMessages;
import com.sk89q.worldguard.util.logging.RecordMessagePrefixer;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The main class for WorldGuard as a Bukkit plugin.
 */
public class WorldGuardPlugin extends JavaPlugin {

    private static final int BSTATS_SERVICE_ID = 34427;
    private static final List<String> LOCALE_REQUIRED_MESSAGES = List.of(
            "<red>Укажите язык <#FDBE00>ru<red> или <#FDBE00>en<red> в <#FDBE00>locale.yml<red> и перезапустите сервер",
            "<red>Select <#FDBE00>ru<red> or <#FDBE00>en<red> in <#FDBE00>locale.yml<red> and restart the server");
    private static WorldGuardPlugin inst;
    private static BukkitWorldGuardPlatform platform;
    private PlayerMoveListener playerMoveListener;
    private BukkitMessages messages;
    private BukkitLogs logs;
    private BukkitLocaleManager localeManager;
    private BukkitRegionDefaults regionDefaults;
    private final ConcurrentMap<UUID, BukkitPlayer> playerWrappers = new ConcurrentHashMap<>();
    private Metrics metrics;
    private boolean runtimeStarted;
    private boolean localeSelected;

    /**
     * Construct objects. Actual loading occurs when the plugin is enabled, so
     * this merely instantiates the objects.
     */
    public WorldGuardPlugin() {
        inst = this;
    }

    @Override
    public void onLoad() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            RegionCommands region = new RegionCommands(WorldGuard.getInstance());
            MemberCommands member = new MemberCommands(WorldGuard.getInstance());
            PaperCommandDispatcher regionCommands = new PaperCommandDispatcher(this, "region")
                    .add(new String[]{"define", "def", "d", "create"},
                            "[-w <world>] <id> [owners...]", 1, -1, "gw:", region::define)
                    .add(new String[]{"redefine", "update", "move"},
                            "[-w <world>] <id>", 1, 1, "gw:", region::redefine)
                    .add(new String[]{"claim"}, "<id>", 1, 1, "", region::claim)
                    .add(new String[]{"select", "sel", "s"},
                            "[-w <world>] [id]", 0, 1, "w:", region::select)
                    .add(new String[]{"info", "i"}, "[id]", 0, 1, "usw:", region::info)
                    .add(new String[]{"list"},
                            "[-w world] [-p owner [-n]] [-s] [-i filter] [my|global] [page]",
                            0, 2, "np:w:i:s", region::list)
                    .add(new String[]{"flag", "f"},
                            "<id> <flag> [-w world] [-g group] [value]",
                            2, -1, "g:w:eh:", region::flag)
                    .add(new String[]{"flags"}, "[-p <page>] [id]",
                            0, 2, "p:w:", region::flagHelper)
                    .add(new String[]{"setpriority", "priority", "pri"},
                            "<id> <priority>", 2, 2, "w:", region::setPriority)
                    .add(new String[]{"setparent", "parent", "par"},
                            "<id> [parent-id]", 1, 2, "w:", region::setParent)
                    .add(new String[]{"remove", "delete", "del", "rem"},
                            "<id>", 1, 1, "fuw:", region::remove)
                    .add(new String[]{"load", "reload"}, "[world]",
                            0, -1, "w:", region::load)
                    .add(new String[]{"save", "write"}, "[world]",
                            0, -1, "w:", region::save)
                    .add(new String[]{"teleport", "tp"}, "[-w world] [-c|s] <id>",
                            1, 1, "csw:", region::teleport)
                    .add(new String[]{"toggle-bypass", "bypass"}, "[on|off]",
                            0, -1, "", region::toggleBypass)
                    .add(new String[]{"addmember", "addmem", "am"}, "<id> <members...>",
                            2, -1, "w:", member::addMember)
                    .add(new String[]{"addowner", "ao"}, "<id> <owners...>",
                            2, -1, "w:", member::addOwner)
                    .add(new String[]{"removemember", "remmember", "removemem", "remmem", "rm"},
                            "<id> <members...>", 1, -1, "aw:", member::removeMember)
                    .add(new String[]{"removeowner", "remowner", "ro"}, "<id> <owners...>",
                            1, -1, "aw:", member::removeOwner);
            event.registrar().register(getPluginMeta(), regionCommands.build(),
                    "WorldGuard region commands", List.of("regions", "rg"));

            WorldGuardCommands worldGuard = new WorldGuardCommands(WorldGuard.getInstance());
            PaperCommandDispatcher worldGuardCommands = new PaperCommandDispatcher(this, "worldguard")
                    .add(new String[]{"reload"}, "", 0, 0, "", worldGuard::reload,
                            "worldguard.reload");
            event.registrar().register(getPluginMeta(), worldGuardCommands.build(),
                    "WorldGuard commands", List.of("wg"));
        });
    }

    /**
     * Get the current instance of WorldGuard
     * @return WorldGuardPlugin instance
     */
    public static WorldGuardPlugin inst() {
        return inst;
    }

    /**
     * Called on plugin enable.
     */
    @Override
    public void onEnable() {
        BukkitLogs.installBundledDefaults(this, "en");
        configureLogger();

        localeManager = new BukkitLocaleManager(this);
        localeSelected = localeManager.prepareOnEnable();
        if (!localeSelected) {
            localeManager.installRuntimeFallback();
        }
        BukkitLogs.installBundledDefaults(this, localeManager.currentLocale());
        logs = new BukkitLogs(this);
        logs.load();
        regionDefaults = new BukkitRegionDefaults(this);
        messages = new BukkitMessages(this);

        // Catch bad things being done by naughty plugins that include WorldGuard's classes
        ClassSourceValidator verifier = new ClassSourceValidator(this);
        verifier.reportMismatches(ImmutableList.of(WorldGuard.class, ProtectedRegion.class, Flag.class));

        PermissionsResolverManager.initialize(this);

        WorldGuard.getInstance().setPlatform(platform = new BukkitWorldGuardPlatform()); // Initialise WorldGuard
        WorldGuard.getInstance().setup();
        runtimeStarted = true;
        metrics = new Metrics(this, BSTATS_SERVICE_ID);

        if (!localeSelected) {
            ((SimpleFlagRegistry) WorldGuard.getInstance().getFlagRegistry()).setInitialized(true);
            getLogger().warning("Укажите язык ru или en в locale.yml и перезапустите сервер");
            getLogger().warning("Select ru or en in locale.yml and restart the server");
            return;
        }

        BukkitSessionManager sessionManager = (BukkitSessionManager) platform.getSessionManager();

        if (this.isFolia()) {
            getServer().getGlobalRegionScheduler().runAtFixedRate(
                    this, ignored -> sessionManager.run(), BukkitSessionManager.RUN_DELAY, BukkitSessionManager.RUN_DELAY);
        } else {
            getServer().getScheduler().scheduleSyncRepeatingTask(this, sessionManager, BukkitSessionManager.RUN_DELAY, BukkitSessionManager.RUN_DELAY);
        }
        new SelectionVisualizer(this).start();

        // Register events
        getServer().getPluginManager().registerEvents(sessionManager, this);
        (new WorldGuardPlayerListener(this)).registerEvents();
        (new WorldGuardBlockListener(this)).registerEvents();
        (new WorldGuardEntityListener(this)).registerEvents();
        (new WorldGuardWeatherListener(this)).registerEvents();
        (new WorldGuardVehicleListener(this)).registerEvents();
        (new WorldGuardHangingListener(this)).registerEvents();

        // Modules
        (playerMoveListener = new PlayerMoveListener(this)).registerEvents();
        (new RegionProtectionListener(this)).registerEvents();
        (new RegionFlagsListener(this)).registerEvents();
        (new EventAbstractionListener(this)).registerEvents();
        (new InvincibilityListener(this)).registerEvents();

        // handle worlds separately to initialize already loaded worlds
        WorldGuardWorldListener worldListener = (new WorldGuardWorldListener(this));
        for (World world : getServer().getWorlds()) {
            worldListener.initWorld(world);
        }
        worldListener.registerEvents();

        if (this.isFolia()) {
            for (Player player : Bukkit.getServer().getOnlinePlayers()) {
                player.getScheduler().run(this, ignored -> {
                    ProcessPlayerEvent event = new ProcessPlayerEvent(player);
                    Events.fire(event);
                }, null);
            }
        } else {
            Bukkit.getScheduler().runTask(this, () -> {
                for (Player player : Bukkit.getServer().getOnlinePlayers()) {
                    ProcessPlayerEvent event = new ProcessPlayerEvent(player);
                    Events.fire(event);
                }
            });
        }

        ((SimpleFlagRegistry) WorldGuard.getInstance().getFlagRegistry()).setInitialized(true);

    }

    @Override
    public void onDisable() {
        if (!runtimeStarted) {
            return;
        }
        runtimeStarted = false;
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
        WorldGuard.getInstance().disable();
        playerWrappers.clear();
        if (this.isFolia()) {
            this.getServer().getGlobalRegionScheduler().cancelTasks(this);
            this.getServer().getAsyncScheduler().cancelTasks(this);
        } else {
            this.getServer().getScheduler().cancelTasks(this);
        }
    }

    /**
     * Check whether a player is in a group.
     * This calls the corresponding method in PermissionsResolverManager
     *
     * @param player The player to check
     * @param group The group
     * @return whether {@code player} is in {@code group}
     */
    public boolean inGroup(OfflinePlayer player, String group) {
        try {
            return PermissionsResolverManager.getInstance().inGroup(player, group);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE,
                    "@wglog:permissionGroupLookupFailed@" + player.getName(), t);
            return false;
        }
    }

    /**
     * Get the groups of a player.
     * This calls the corresponding method in PermissionsResolverManager.
     * @param player The player to check
     * @return The names of each group the playe is in.
     */
    public String[] getGroups(OfflinePlayer player) {
        try {
            return PermissionsResolverManager.getInstance().getGroups(player);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE,
                    "@wglog:permissionGroupsLookupFailed@" + player.getName(), t);
            return new String[0];
        }
    }

    /**
     * Checks permissions.
     *
     * @param sender The sender to check the permission on.
     * @param perm The permission to check the permission on.
     * @return whether {@code sender} has {@code perm}
     */
    public boolean hasPermission(CommandSender sender, String perm) {
        if (sender.isOp()) {
            return true;
        }

        // Invoke the permissions resolver
        if (sender instanceof Player) {
            Player player = (Player) sender;
            return PermissionsResolverManager.getInstance().hasPermission(player.getWorld().getName(), player, perm);
        }

        return false;
    }

    /**
     * Checks permissions and throws an exception if permission is not met.
     *
     * @param sender The sender to check the permission on.
     * @param perm The permission to check the permission on.
     * @throws CommandPermissionsException if {@code sender} doesn't have {@code perm}
     */
    public void checkPermission(CommandSender sender, String perm)
            throws CommandPermissionsException {
        if (!hasPermission(sender, perm)) {
            throw new CommandPermissionsException();
        }
    }

    /**
     * Gets a copy of the WorldEdit plugin.
     *
     * @return The WorldEditPlugin instance
     * @throws CommandException If there is no WorldEditPlugin available
     */
    public WorldEditPlugin getWorldEdit() throws CommandException {
        Plugin worldEdit = getServer().getPluginManager().getPlugin("WorldEdit");
        if (worldEdit == null) {
            throw new CommandException("@wg:worldEditMissing@");
        } else if (!worldEdit.isEnabled()) {
            throw new CommandException("@wg:worldEditDisabled@");
        }

        if (worldEdit instanceof WorldEditPlugin) {
            return (WorldEditPlugin) worldEdit;
        } else {
            throw new CommandException("@wg:worldEditDetectionFailed@");
        }
    }

    /**
     * Wrap a player as a LocalPlayer.
     *
     * @param player The player to wrap
     * @return The wrapped player
     */
    public LocalPlayer wrapPlayer(Player player) {
        return playerWrappers.computeIfAbsent(
                player.getUniqueId(), ignored -> new BukkitPlayer(this, player));
    }

    /**
     * Wrap a player as a LocalPlayer.
     *
     * @param player The player to wrap
     * @param silenced True to silence messages
     * @return The wrapped player
     */
    public LocalPlayer wrapPlayer(Player player, boolean silenced) {
        return silenced ? new BukkitPlayer(this, player, true) : wrapPlayer(player);
    }

    public void forgetPlayer(Player player) {
        playerWrappers.remove(player.getUniqueId());
    }

    public Actor wrapCommandSender(CommandSender sender) {
        if (sender instanceof Player player) {
            if (Entities.isNPC(player)) return null;
            return wrapPlayer(player);
        }

        try {
            return new BukkitWorldGuardCommandSender(getWorldEdit(), sender, messages);
        } catch (CommandException e) {
            getLogger().log(Level.SEVERE,
                    "@wglog:commandSenderWrapFailed@" + sender.getName(), e);
        }
        return null;
    }

    public CommandSender unwrapActor(Actor sender) {
        if (sender instanceof BukkitPlayer) {
            return ((BukkitPlayer) sender).getPlayer();
        } else if (sender instanceof BukkitCommandSender bukkitSender) {
            return bukkitSender.getSender();
        } else {
            throw new IllegalArgumentException("Unknown actor type. Please report");
        }
    }

    /**
     * Wrap a player as a LocalPlayer.
     *
     * <p>This implementation is incomplete -- permissions cannot be checked.</p>
     *
     * @param player The player to wrap
     * @return The wrapped player
     */
    public LocalPlayer wrapOfflinePlayer(OfflinePlayer player) {
        return new BukkitOfflinePlayer(this, player);
    }

    /**
     * Internal method. Do not use as API.
     */
    public BukkitConfigurationManager getConfigManager() {
        return platform.getGlobalStateManager();
    }

    public BukkitMessages getMessages() {
        return messages;
    }

    public boolean isOperational() {
        return runtimeStarted && localeSelected;
    }

    boolean isLocaleSelected() {
        return localeSelected;
    }

    public void sendLocaleRequired(CommandSender sender) {
        MMSupport.send(sender, LOCALE_REQUIRED_MESSAGES);
    }

    public BukkitLogs getLogs() {
        return logs;
    }

    BukkitRegionDefaults getRegionDefaults() {
        return regionDefaults;
    }

    BukkitLocaleManager getLocaleManager() {
        return localeManager;
    }

    InputStream getDefaultConfigurationResource(String name) {
        return localeManager.openDefaultResource(name);
    }

    /**
     * Return a protection query helper object that can be used by another
     * plugin to test whether WorldGuard permits an action at a particular
     * place.
     *
     * @return an instance
     */
    public ProtectionQuery createProtectionQuery() {
        return new ProtectionQuery();
    }

    /**
     * Configure WorldGuard's loggers.
     */
    private void configureLogger() {
        RecordMessagePrefixer.register(Logger.getLogger("com.sk89q.worldguard"), "[WorldGuard] ");
        getLogger().setFilter(record -> {
            String message = LogMessages.resolve(record.getMessage());
            if (message.isEmpty()) return false;
            record.setMessage(message);
            return true;
        });
    }

    /**
     * Create a default configuration file from the .jar.
     *
     * @param actual The destination file
     * @param defaultName The name of the file inside the selected locale folder
     */
    public void createDefaultConfiguration(File actual, String defaultName) {

        // Make parent directories
        File parent = actual.getParentFile();
        if (!parent.exists()) {
            parent.mkdirs();
        }

        if (actual.exists()) {
            return;
        }

        try (InputStream stream = getDefaultConfigurationResource(defaultName)){
            if (stream == null) throw new FileNotFoundException();
            copyDefaultConfig(stream, actual, defaultName);
        } catch (IOException e) {
            getLogger().severe("@wglog:unableReadDefaultConfiguration@" + defaultName);
        }

    }

    private void copyDefaultConfig(InputStream input, File actual, String name) {
        try (FileOutputStream output = new FileOutputStream(actual)) {
            byte[] buf = new byte[8192];
            int length;
            while ((length = input.read(buf)) > 0) {
                output.write(buf, 0, length);
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "@wglog:unableWriteDefaultConfiguration@", e);
        }
    }

    public PlayerMoveListener getPlayerMoveListener() {
        return playerMoveListener;
    }

    private final LazyReference<Boolean> folia = LazyReference.from(() -> {
        try {
            return ServerBuildInfo.buildInfo().isBrandCompatible(net.kyori.adventure.key.Key.key("papermc", "folia"));
        } catch (Throwable t) {
            // Ignore, this likely means an outdated version.
            getLogger().log(Level.WARNING, "@wglog:failedFoliaCheck@", t);
        }

        return false;
    });

    public boolean isFolia() {
        return folia.getValue();
    }

}
