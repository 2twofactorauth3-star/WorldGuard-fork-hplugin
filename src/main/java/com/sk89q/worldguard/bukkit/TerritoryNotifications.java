package com.sk89q.worldguard.bukkit;

import com.sk89q.util.yaml.YAMLFormat;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.event.player.ProcessPlayerEvent;
import com.sk89q.worldguard.bukkit.util.Entities;
import com.sk89q.worldguard.config.TerritoryNotificationSettings;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

final class TerritoryNotifications implements Listener {

    private final WorldGuardPlugin plugin;
    private final File file;
    private final ConcurrentMap<UUID, TerritoryState> states = new ConcurrentHashMap<>();
    private volatile TerritoryNotificationSettings settings;
    private Runnable cancelTimer;

    TerritoryNotifications(WorldGuardPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "territoryNotifications.yml");
    }

    void load() {
        plugin.createDefaultConfiguration(file, "territoryNotifications.yml");
        YAMLProcessor config = new YAMLProcessor(file, false, YAMLFormat.EXTENDED);
        try {
            BukkitConfigValidator.validateNoDuplicateKeys(plugin, file);
            config.load();
            settings = TerritoryNotificationSettings.read(config);
        } catch (IOException | YAMLException | IllegalArgumentException e) {
            settings = null;
            plugin.getLogger().log(Level.SEVERE, "@wglog:unableLoadTerritoryNotifications@", e);
        }
    }

    void start() {
        if (cancelTimer != null) return;
        if (plugin.isFolia()) {
            var task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                    plugin, ignored -> tick(), 20, 20);
            cancelTimer = task::cancel;
        } else {
            var task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
            cancelTimer = task::cancel;
        }
    }

    void close() {
        if (cancelTimer != null) {
            cancelTimer.run();
            cancelTimer = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) clear(player);
        states.clear();
    }

    private void tick() {
        TerritoryNotificationSettings current = settings;
        if ((current == null || !current.enabled) && states.isEmpty()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isFolia()) {
                player.getScheduler().run(plugin, ignored -> refresh(player), null);
            } else {
                refresh(player);
            }
        }
    }

    private void refresh(Player player) {
        if (!player.isOnline()) return;
        TerritoryState state = states.get(player.getUniqueId());
        TerritoryNotificationSettings current = settings;
        if (state == null || state.settings != current) {
            update(player, player.getLocation(), current != null && current.showOnJoin);
        } else {
            state.presentation.refresh(player, System.nanoTime());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld().equals(to.getWorld()) && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        update(event.getPlayer(), to, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) update(event.getPlayer(), event.getTo(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerProcess(ProcessPlayerEvent event) {
        TerritoryNotificationSettings current = settings;
        if (current != null) update(event.getPlayer(), event.getPlayer().getLocation(), current.showOnJoin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        update(event.getPlayer(), event.getRespawnLocation(), true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    private void update(Player player, Location location, boolean send) {
        TerritoryNotificationSettings current = settings;
        if (current == null || !current.appliesTo(location.getWorld().getName()) || Entities.isNPC(player)) {
            clear(player);
            return;
        }
        BukkitWorldConfiguration worldConfig = ((BukkitConfigurationManager) WorldGuard.getInstance()
                .getPlatform().getGlobalStateManager()).get(location.getWorld());
        if (!worldConfig.useRegions) {
            clear(player);
            return;
        }
        BukkitRegionContainer container = (BukkitRegionContainer) WorldGuard.getInstance()
                .getPlatform().getRegionContainer();
        RegionManager manager = container.get(location.getWorld());
        if (manager == null) {
            clear(player);
            return;
        }
        ApplicableRegionSet regions = container.createQuery().getApplicableRegions(BukkitAdapter.adapt(location));
        if (regions.isVirtual()) {
            clear(player);
            return;
        }
        ProtectedRegion region = TerritoryNotificationSettings.selectRegion(
                regions, manager.getRegion(ProtectedRegion.GLOBAL_REGION));
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        var notification = current.notification(localPlayer, location.getWorld().getName(), region);
        TerritoryState previous = states.get(player.getUniqueId());
        if (previous != null && previous.matches(location.getWorld().getUID(), region, current, notification)) return;
        if (previous != null) previous.presentation.clear(player);
        long now = System.nanoTime();
        TerritoryPresentation presentation = new TerritoryPresentation(notification,
                TerritoryPresentation.renderer(plugin.getMessages(), notification,
                        region == null ? ProtectedRegion.GLOBAL_REGION : region.getId(),
                        location.getWorld().getName(), player.getName()), now);
        states.put(player.getUniqueId(), new TerritoryState(
                location.getWorld().getUID(), region, current, notification, presentation));
        if (send) presentation.show(player, now);
    }

    private void clear(Player player) {
        TerritoryState previous = states.remove(player.getUniqueId());
        if (previous != null) previous.presentation.clear(player);
    }

    private static final class TerritoryState {
        public final UUID world;
        public final ProtectedRegion region;
        public final TerritoryNotificationSettings settings;
        public final TerritoryNotificationSettings.Notification notification;
        public final TerritoryPresentation presentation;

        private TerritoryState(UUID world, ProtectedRegion region, TerritoryNotificationSettings settings,
                               TerritoryNotificationSettings.Notification notification, TerritoryPresentation presentation) {
            this.world = world;
            this.region = region;
            this.settings = settings;
            this.notification = notification;
            this.presentation = presentation;
        }

        private boolean matches(UUID world, ProtectedRegion region, TerritoryNotificationSettings settings,
                                TerritoryNotificationSettings.Notification notification) {
            return this.world.equals(world) && this.region == region
                    && this.settings == settings && this.notification == notification;
        }
    }
}
