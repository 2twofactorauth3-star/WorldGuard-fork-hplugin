package com.sk89q.worldguard.bukkit;

import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.event.player.ProcessPlayerEvent;
import com.sk89q.worldguard.bukkit.util.Entities;
import com.sk89q.worldguard.config.TerritoryNotificationSettings;
import com.sk89q.worldguard.config.TerritoryNotificationSettings.Notification;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

final class TerritoryNotifications implements Listener {

    private final WorldGuardPlugin plugin;
    private final ConcurrentMap<UUID, Tracker> trackers = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Tracker> pending = new ConcurrentLinkedQueue<>();
    private volatile TerritoryNotificationSettings settings;
    private Runnable cancelTimer;
    private int ticks;

    TerritoryNotifications(WorldGuardPlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        File file = new File(plugin.getDataFolder(), "territoryNotifications.yml");
        plugin.createDefaultConfiguration(file, "territoryNotifications.yml");
        YAMLProcessor config = new CommentedYamlProcessor(
                file, () -> plugin.getDefaultConfigurationResource("territoryNotifications.yml"), plugin.getLogger());
        try {
            BukkitConfigValidator.validateNoDuplicateKeys(plugin, file);
            config.load();
            boolean migrated = migrateLegacyZones(config);
            settings = TerritoryNotificationSettings.read(config);
            if (migrated) config.save();
        } catch (IOException | YAMLException | IllegalArgumentException e) {
            settings = null;
            plugin.getLogger().log(Level.SEVERE, "@wglog:unableLoadTerritoryNotifications@", e);
        }
        if (cancelTimer != null) {
            for (Player player : Bukkit.getOnlinePlayers()) requestUpdate(player);
        }
    }

    static boolean migrateLegacyZones(YAMLProcessor config) {
        if (config.getProperty("settings.zones") != null) return false;
        config.setProperty("settings.zones", Map.of(
                "allowedWorlds", Map.of("type", "whitelist", "list", List.of(Map.of(
                        "worlds", config.getStringList("settings.worlds", List.of("*")), "regions", "*"))),
                "excludedWorlds", Map.of("type", "blacklist", "list", List.of(Map.of(
                        "worlds", config.getStringList("settings.excludedWorlds", List.of("forhpvp")), "regions", "*")))));
        config.removeProperty("settings.worlds");
        config.removeProperty("settings.excludedWorlds");
        return true;
    }

    void start() {
        if (cancelTimer != null) return;
        if (plugin.isFolia()) {
            var task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                    plugin, ignored -> tick(), 1, 1);
            cancelTimer = task::cancel;
        } else {
            var task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
            cancelTimer = task::cancel;
        }
        for (Player player : Bukkit.getOnlinePlayers()) requestUpdate(player);
    }

    void close() {
        if (cancelTimer != null) {
            cancelTimer.run();
            cancelTimer = null;
        }
        for (Tracker tracker : trackers.values()) clear(tracker);
        trackers.clear();
        pending.clear();
    }

    void requestUpdate(Player player) {
        TerritoryNotificationSettings current = settings;
        Tracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) {
            if (current == null || !current.appliesTo(player.getWorld().getName()) || Entities.isNPC(player)) return;
            tracker = trackers.computeIfAbsent(player.getUniqueId(),
                    ignored -> new Tracker(player, plugin.wrapPlayer(player)));
        } else if (tracker.disabled && tracker.settings == current && tracker.world == player.getWorld()) {
            return;
        } else if (tracker.fixed != null && tracker.presentation != null && tracker.settings == current
                && tracker.world == player.getWorld() && tracker.worldConfig.useRegions) {
            return;
        }
        if (tracker.queued.compareAndSet(false, true)) pending.offer(tracker);
    }

    void cacheRegions(Player player, Location location, RegionManager manager, ApplicableRegionSet regions) {
        Tracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null || tracker.fixed != null || regions == null || regions.isVirtual()) return;
        tracker.cachedManager = manager;
        tracker.cachedWorld = location.getWorld();
        tracker.cachedX = location.getBlockX();
        tracker.cachedY = location.getBlockY();
        tracker.cachedZ = location.getBlockZ();
        tracker.cachedQueryRevision = manager.getQueryRevision();
        tracker.cachedStructureRevision = ProtectedRegion.structureRevision();
        tracker.cachedRegions = regions;
    }

    private void tick() {
        TerritoryNotificationSettings current = settings;
        ticks = ticks == Integer.MAX_VALUE ? 1 : ticks + 1;
        int period = current == null ? 20 : current.updatePeriodTicks;
        boolean refresh = ticks % 20 == 0;
        if (ticks % period == 0) drain(refresh);
        if (refresh) {
            for (Tracker tracker : trackers.values()) {
                if (tracker.lastDispatchTick != ticks && (tracker.presentation != null && tracker.presentation.needsRefresh()
                        || tracker.settings != current || tracker.retry)) dispatch(tracker, false, true);
            }
        }
    }

    private void drain(boolean refresh) {
        int remaining = trackers.size();
        while (remaining-- > 0) {
            Tracker tracker = pending.poll();
            if (tracker == null) break;
            dispatch(tracker, true, refresh);
        }
    }

    private void dispatch(Tracker tracker, boolean update, boolean refresh) {
        tracker.lastDispatchTick = ticks;
        if (plugin.isFolia()) {
            tracker.player.getScheduler().run(plugin, ignored -> process(tracker, update, refresh),
                    () -> retire(tracker));
        } else {
            process(tracker, update, refresh);
        }
    }

    private void process(Tracker tracker, boolean update, boolean refresh) {
        Player player = tracker.player;
        if (!player.isOnline() || trackers.get(player.getUniqueId()) != tracker) return;
        if (refresh && Entities.isNPC(player)) {
            trackers.remove(player.getUniqueId(), tracker);
            pending.remove(tracker);
            clear(tracker);
            return;
        }
        if (update || tracker.settings != settings || tracker.retry) {
            tracker.queued.set(false);
            update(tracker, player.getLocation());
        } else if (tracker.fixed != null && !tracker.worldConfig.useRegions) {
            clear(tracker);
        }
        if (refresh && tracker.presentation != null) tracker.presentation.refresh(player, System.nanoTime());
    }

    private void retire(Tracker tracker) {
        trackers.remove(tracker.player.getUniqueId(), tracker);
        pending.remove(tracker);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) requestUpdate(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerProcess(ProcessPlayerEvent event) {
        requestUpdate(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        requestUpdate(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Tracker tracker = trackers.remove(event.getPlayer().getUniqueId());
        if (tracker != null) {
            pending.remove(tracker);
            clear(tracker);
        }
    }

    private void update(Tracker tracker, Location location) {
        TerritoryNotificationSettings current = settings;
        org.bukkit.World world = location.getWorld();
        boolean changed = tracker.settings != current || tracker.world != world;
        boolean reloaded = tracker.settings != current && tracker.world == world;
        tracker.disabled = current == null || !current.appliesTo(world.getName());
        tracker.retry = false;
        if (changed) configure(tracker, current, world);
        if (tracker.disabled || !tracker.worldConfig.useRegions) {
            clear(tracker);
            return;
        }
        BukkitRegionContainer container = (BukkitRegionContainer) WorldGuard.getInstance()
                .getPlatform().getRegionContainer();
        RegionManager manager = container.get(world);
        if (manager == null) {
            tracker.retry = true;
            clear(tracker);
            return;
        }
        ProtectedRegion region = tracker.fixed == null ? selectRegion(tracker, location, manager) : null;
        if (!current.appliesTo(world.getName(), region)) {
            clear(tracker);
            return;
        }
        Notification notification = tracker.fixed == null
                ? current.notification(tracker.localPlayer, world.getName(), region) : tracker.fixed;
        present(tracker, current, world, region, notification, changed, reloaded);
    }

    private void present(Tracker tracker, TerritoryNotificationSettings current, org.bukkit.World world,
                         ProtectedRegion region, Notification notification, boolean changed, boolean reloaded) {
        if (!changed && tracker.notification == notification && tracker.region == region
                && tracker.presentation != null) return;
        clear(tracker);
        tracker.region = region;
        tracker.notification = notification;
        long now = System.nanoTime();
        TerritoryPresentation presentation = new TerritoryPresentation(notification,
                TerritoryPresentation.renderer(plugin.getMessages(), notification,
                        region == null ? ProtectedRegion.GLOBAL_REGION : region.getId(),
                        world.getName(), tracker.player.getName(), tracker.player), now);
        if (current.showOnJoin || !tracker.initial && !reloaded) presentation.show(tracker.player, now);
        tracker.presentation = presentation;
        tracker.initial = false;
    }

    private void configure(Tracker tracker, TerritoryNotificationSettings current, org.bukkit.World world) {
        tracker.world = world;
        tracker.fixed = null;
        if (!tracker.disabled) {
            tracker.worldConfig = plugin.getConfigManager().get(world);
            Notification fixed = current.fixedNotification(world.getName());
            if (fixed != null && !TerritoryPresentation.requiresRegion(plugin.getMessages(), fixed)) tracker.fixed = fixed;
        }
        tracker.settings = current;
    }

    private ProtectedRegion selectRegion(Tracker tracker, Location location, RegionManager manager) {
        BlockVector3 position = BukkitAdapter.asBlockVector(location);
        if (tracker.cachedManager != manager || tracker.cachedWorld != location.getWorld()
                || position.x() != tracker.cachedX || position.y() != tracker.cachedY || position.z() != tracker.cachedZ
                || tracker.cachedQueryRevision != manager.getQueryRevision()
                || tracker.cachedStructureRevision != ProtectedRegion.structureRevision()) {
            return manager.getHighestPriorityRegion(position);
        }
        return selectCachedRegion(tracker.cachedRegions, position, manager.getRegion(ProtectedRegion.GLOBAL_REGION));
    }

    static ProtectedRegion selectCachedRegion(ApplicableRegionSet regions, BlockVector3 position, ProtectedRegion global) {
        ProtectedRegion selected = null;
        for (ProtectedRegion candidate : regions) {
            if (!candidate.isPhysicalArea() || !candidate.contains(position)) continue;
            if (selected == null || candidate.getPriority() > selected.getPriority()
                    || candidate.getPriority() == selected.getPriority()
                    && candidate.getId().compareTo(selected.getId()) < 0) selected = candidate;
        }
        return selected == null ? global : selected;
    }

    private void clear(Tracker tracker) {
        if (tracker.presentation != null) tracker.presentation.clear(tracker.player);
        tracker.presentation = null;
        tracker.cachedRegions = null;
        tracker.cachedManager = null;
        tracker.region = null;
        tracker.notification = null;
    }

    private static final class Tracker {
        private final Player player;
        private final LocalPlayer localPlayer;
        private final AtomicBoolean queued = new AtomicBoolean();
        private volatile TerritoryNotificationSettings settings;
        private org.bukkit.World world;
        private BukkitWorldConfiguration worldConfig;
        private ProtectedRegion region;
        private Notification notification;
        private Notification fixed;
        private volatile TerritoryPresentation presentation;
        private RegionManager cachedManager;
        private org.bukkit.World cachedWorld;
        private int cachedX;
        private int cachedY;
        private int cachedZ;
        private ApplicableRegionSet cachedRegions;
        private long cachedQueryRevision;
        private long cachedStructureRevision;
        private boolean initial = true;
        private volatile boolean disabled;
        private volatile boolean retry;
        private int lastDispatchTick = -1;

        private Tracker(Player player, LocalPlayer localPlayer) {
            this.player = player;
            this.localPlayer = localPlayer;
        }
    }
}
