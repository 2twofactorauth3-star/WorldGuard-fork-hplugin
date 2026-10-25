package com.sk89q.worldguard.config;

import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@SuppressWarnings("PatternValidation")
public final class TerritoryNotificationSettings {

    public final boolean enabled;
    public final boolean showOnJoin;
    public final boolean includeMembers;
    public final int updatePeriodTicks;
    public final Set<String> worlds;
    public final Set<String> excludedWorlds;
    public final Notification free;
    public final Notification occupied;
    public final Notification own;
    public final List<CustomName> customNames;

    private TerritoryNotificationSettings(YAMLProcessor config) {
        enabled = config.getBoolean("settings.enable", true);
        showOnJoin = config.getBoolean("settings.showOnJoin", true);
        includeMembers = config.getBoolean("settings.includeMembers", true);
        updatePeriodTicks = Math.clamp(config.getInt("settings.updatePeriodTicks", 20), 20, 60);
        worlds = Set.copyOf(config.getStringList("settings.worlds", List.of("*")));
        excludedWorlds = Set.copyOf(config.getStringList("settings.excludedWorlds", List.of("forhpvp")));
        free = new Notification(config, "settings.free");
        occupied = new Notification(config, "settings.occupied");
        own = new Notification(config, "settings.own");
        customNames = readCustomNames(config);
    }

    public static TerritoryNotificationSettings read(YAMLProcessor config) {
        return new TerritoryNotificationSettings(config);
    }

    public boolean appliesTo(String world) {
        return enabled && !excludedWorlds.contains(world) && (worlds.contains("*") || worlds.contains(world));
    }

    public Notification fixedNotification(String world) {
        for (CustomName customName : customNames) {
            if (!customName.worlds.contains("*") && !customName.worlds.contains(world)) continue;
            return customName.regions.contains("*") ? customName.notification : null;
        }
        return null;
    }

    public Notification notification(LocalPlayer player, String world, ProtectedRegion region) {
        String regionId = region == null ? ProtectedRegion.GLOBAL_REGION : region.getId();
        for (CustomName customName : customNames) {
            if (customName.matches(world, regionId)) return customName.notification;
        }
        if (region != null && region.isPhysicalArea()) {
            boolean belongsToPlayer = includeMembers ? region.isMember(player) : region.isOwner(player);
            return belongsToPlayer ? own : occupied;
        }
        return free;
    }

    public static ProtectedRegion selectRegion(Iterable<ProtectedRegion> regions, ProtectedRegion global) {
        ProtectedRegion selected = null;
        for (ProtectedRegion region : regions) {
            if (!region.isPhysicalArea()) continue;
            if (selected == null || region.getPriority() > selected.getPriority()
                    || region.getPriority() == selected.getPriority()
                    && region.getId().compareTo(selected.getId()) < 0) {
                selected = region;
            }
        }
        return selected == null ? global : selected;
    }

    private static List<CustomName> readCustomNames(YAMLProcessor config) {
        List<String> keys = config.getKeys("settings.customNames");
        if (keys == null) return List.of();
        List<CustomName> names = new ArrayList<>(keys.size());
        for (String key : keys) {
            String path = "settings.customNames." + key;
            List<String> regionIds = new ArrayList<>();
            for (String id : config.getStringList(path + ".regions", List.of())) {
                regionIds.add(id.toLowerCase(Locale.ROOT));
            }
            names.add(new CustomName(
                    Set.copyOf(config.getStringList(path + ".worlds", List.of())),
                    Set.copyOf(regionIds), new Notification(config, path)));
        }
        return List.copyOf(names);
    }

    public static final class CustomName {
        public final Set<String> worlds;
        public final Set<String> regions;
        public final Notification notification;

        private CustomName(Set<String> worlds, Set<String> regions, Notification notification) {
            this.worlds = worlds;
            this.regions = regions;
            this.notification = notification;
        }

        public boolean matches(String world, String region) {
            return (worlds.contains("*") || worlds.contains(world))
                    && (regions.contains("*") || regions.contains(region));
        }
    }

    public static final class Notification {
        public final String name;
        public final Sound sound;
        public final TitleSettings title;
        public final TimedChannel actionBar;
        public final TimedChannel bossBar;
        public final BossBar.Color bossBarColor;
        public final BossBar.Overlay bossBarOverlay;
        public final float bossBarProgress;
        public final List<String> messages;
        public final long repeatNanos;

        private Notification(YAMLProcessor config, String path) {
            name = config.getString(path + ".name", "");
            sound = readSound(config, path + ".sound");
            title = new TitleSettings(config, path + ".title");
            actionBar = new TimedChannel(config, path + ".actionBar", true);
            bossBar = new TimedChannel(config, path + ".bossBar", false);
            bossBarColor = BossBar.Color.valueOf(config.getString(path + ".bossBar.color", "YELLOW")
                    .toUpperCase(Locale.ROOT));
            bossBarOverlay = BossBar.Overlay.valueOf(config.getString(path + ".bossBar.overlay", "PROGRESS")
                    .toUpperCase(Locale.ROOT));
            bossBarProgress = (float) boundedNumber(config, path + ".bossBar.progress", 1, 0, 1);
            messages = config.getBoolean(path + ".messages.enable", false)
                    ? readLines(config.getProperty(path + ".messages.message")) : List.of();
            long repeat = seconds(config, path + ".messages.repeatSeconds", -1);
            repeatNanos = repeat > 0 ? repeat : -1;
        }
    }

    public static final class TimedChannel {
        public final boolean enabled;
        public final String message;
        public final long durationNanos;

        private TimedChannel(YAMLProcessor config, String path, boolean enabledByDefault) {
            enabled = config.getBoolean(path + ".enable", enabledByDefault);
            message = config.getString(path + ".message", "{name}");
            durationNanos = seconds(config, path + ".durationSeconds", -1);
        }
    }

    public static final class TitleSettings {
        public final boolean enabled;
        public final String title;
        public final String subtitle;
        public final Title.Times times;

        private TitleSettings(YAMLProcessor config, String path) {
            enabled = config.getBoolean(path + ".enable", false);
            title = config.getString(path + ".title", "{name}");
            subtitle = config.getString(path + ".subtitle", "");
            times = Title.Times.times(
                    Duration.ofNanos(Math.max(0, seconds(config, path + ".fadeInSeconds", 0.5))),
                    Duration.ofNanos(Math.max(0, seconds(config, path + ".staySeconds", 3))),
                    Duration.ofNanos(Math.max(0, seconds(config, path + ".fadeOutSeconds", 0.5))));
        }
    }

    private static Sound readSound(YAMLProcessor config, String path) {
        if (!config.getBoolean(path + ".enable", false)) return null;
        return Sound.sound(Key.key(config.getString(path + ".type", "minecraft:block.note_block.pling")),
                Sound.Source.MASTER,
                (float) boundedNumber(config, path + ".volume", 1, 0, 10),
                (float) boundedNumber(config, path + ".pitch", 1, 0.01, 2));
    }

    private static List<String> readLines(Object value) {
        if (value instanceof String line) return List.of(line);
        if (!(value instanceof List<?> lines)) return List.of();
        List<String> result = new ArrayList<>(lines.size());
        for (Object line : lines) {
            if (line != null) result.add(String.valueOf(line));
        }
        return List.copyOf(result);
    }

    private static long seconds(YAMLProcessor config, String path, double fallback) {
        double value = number(config, path, fallback);
        return value < 0 ? -1 : (long) Math.min(value * 1_000_000_000, Long.MAX_VALUE);
    }

    private static double boundedNumber(YAMLProcessor config, String path, double fallback, double min, double max) {
        return Math.clamp(number(config, path, fallback), min, max);
    }

    private static double number(YAMLProcessor config, String path, double fallback) {
        Object raw = config.getProperty(path);
        double value = raw == null ? fallback : Double.parseDouble(String.valueOf(raw));
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid number: " + path);
        return value;
    }
}
