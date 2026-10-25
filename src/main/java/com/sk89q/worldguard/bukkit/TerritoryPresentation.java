package com.sk89q.worldguard.bukkit;

import com.sk89q.worldguard.bukkit.util.MMSupport;
import com.sk89q.worldguard.config.TerritoryNotificationSettings.Notification;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class TerritoryPresentation {

    private static final long MIN_ACTION_BAR_REFRESH_NANOS = 500_000_000L;
    private final Notification notification;
    private final Component actionBar;
    private final BossBar bossBar;
    private final Title title;
    private final List<Component> messages;
    private final long started;
    private long lastRepeat;
    private long lastActionBarElapsed;
    private boolean actionBarVisible;
    private boolean bossBarVisible;
    private boolean titleVisible;
    private boolean shown;

    TerritoryPresentation(Notification notification, Function<String, Component> renderer, long now) {
        this.notification = notification;
        actionBar = notification.actionBar.enabled ? renderer.apply(notification.actionBar.message) : null;
        bossBar = notification.bossBar.enabled ? BossBar.bossBar(renderer.apply(notification.bossBar.message),
                notification.bossBarProgress, notification.bossBarColor, notification.bossBarOverlay) : null;
        title = notification.title.enabled ? Title.title(renderer.apply(notification.title.title),
                renderer.apply(notification.title.subtitle), notification.title.times) : null;
        List<Component> rendered = new ArrayList<>(notification.messages.size());
        for (String line : notification.messages) rendered.add(renderer.apply(line));
        messages = List.copyOf(rendered);
        started = now;
        lastRepeat = now;
    }

    static Function<String, Component> renderer(
            BukkitMessages messages, Notification notification, String region, String world, String player) {
        Component name = messages.component(placeholders(notification.name, region, world, player));
        return text -> {
            if (text.equals("{name}")) return name;
            String value = placeholders(text, region, world, player);
            return value.contains("{name}")
                    ? messages.component(value.replace("{name}", "<wg_territory_name>"),
                            TagResolver.resolver("wg_territory_name", Tag.selfClosingInserting(name)))
                    : messages.component(value);
        };
    }

    static boolean requiresRegion(BukkitMessages messages, Notification notification) {
        if (notification.sound != null || notification.title.enabled || !notification.messages.isEmpty()) return true;
        if (notification.actionBar.enabled && notification.actionBar.durationNanos >= 0
                || notification.bossBar.enabled && notification.bossBar.durationNanos >= 0) return true;
        if (containsRegion(messages, notification.name)) return true;
        return notification.actionBar.enabled && containsRegion(messages, notification.actionBar.message)
                || notification.bossBar.enabled && containsRegion(messages, notification.bossBar.message);
    }

    private static boolean containsRegion(BukkitMessages messages, String text) {
        for (String line : messages.lines(text)) {
            if (line.contains("{region}")) return true;
        }
        return false;
    }

    private static String placeholders(String text, String region, String world, String player) {
        if (text.contains("{region}")) text = text.replace("{region}", MMSupport.escape(region));
        if (text.contains("{world}")) text = text.replace("{world}", MMSupport.escape(world));
        if (text.contains("{player}")) text = text.replace("{player}", MMSupport.escape(player));
        return text;
    }

    void show(Audience audience, long now) {
        shown = true;
        if (notification.sound != null) audience.playSound(notification.sound);
        if (title != null) {
            audience.showTitle(title);
            titleVisible = true;
        }
        sendMessages(audience);
        refresh(audience, now);
    }

    void refresh(Audience audience, long now) {
        if (!shown) return;
        long elapsed = now - started;
        if (titleVisible && elapsed >= titleDuration()) titleVisible = false;
        refreshActionBar(audience, elapsed);
        refreshBossBar(audience, elapsed);
        if (!messages.isEmpty() && notification.repeatNanos > 0 && now - lastRepeat >= notification.repeatNanos) {
            sendMessages(audience);
            lastRepeat = now;
        }
    }

    boolean needsRefresh() {
        return shown && (actionBarVisible || bossBarVisible && notification.bossBar.durationNanos >= 0
                || titleVisible || !messages.isEmpty() && notification.repeatNanos > 0);
    }

    private void refreshActionBar(Audience audience, long elapsed) {
        if (actionBar == null) return;
        if (active(notification.actionBar.durationNanos, elapsed)) {
            if (!actionBarVisible || elapsed - lastActionBarElapsed >= MIN_ACTION_BAR_REFRESH_NANOS) {
                audience.sendActionBar(actionBar);
                lastActionBarElapsed = elapsed;
                actionBarVisible = true;
            }
        } else if (actionBarVisible) {
            audience.sendActionBar(Component.empty());
            actionBarVisible = false;
        }
    }

    private void refreshBossBar(Audience audience, long elapsed) {
        if (bossBar == null) return;
        boolean active = active(notification.bossBar.durationNanos, elapsed);
        if (active && !bossBarVisible) audience.showBossBar(bossBar);
        if (!active && bossBarVisible) audience.hideBossBar(bossBar);
        bossBarVisible = active;
    }

    void clear(Audience audience) {
        if (bossBarVisible) audience.hideBossBar(bossBar);
        if (actionBarVisible) audience.sendActionBar(Component.empty());
        if (titleVisible) audience.clearTitle();
        actionBarVisible = false;
        bossBarVisible = false;
        titleVisible = false;
        shown = false;
    }

    private void sendMessages(Audience audience) {
        for (Component line : messages) audience.sendMessage(line);
    }

    private static boolean active(long duration, long elapsed) {
        return duration < 0 || elapsed < duration;
    }

    private long titleDuration() {
        var times = notification.title.times;
        double duration = (double) times.fadeIn().toNanos() + times.stay().toNanos() + times.fadeOut().toNanos();
        return (long) Math.min(duration, Long.MAX_VALUE);
    }
}
