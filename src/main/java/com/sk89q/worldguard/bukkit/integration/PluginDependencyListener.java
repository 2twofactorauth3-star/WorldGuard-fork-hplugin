package com.sk89q.worldguard.bukkit.integration;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

public final class PluginDependencyListener implements Listener, AutoCloseable {

    private final Plugin owner;
    private final String dependencyName;
    private final Consumer<Plugin> enabled;
    private final Consumer<Plugin> disabled;
    private boolean started;

    public PluginDependencyListener(Plugin owner, String dependencyName,
                                    Consumer<Plugin> enabled, Consumer<Plugin> disabled) {
        this.owner = owner;
        this.dependencyName = dependencyName;
        this.enabled = enabled;
        this.disabled = disabled;
    }

    public void start() {
        if (started) return;
        owner.getServer().getPluginManager().registerEvents(this, owner);
        started = true;
        Plugin dependency = owner.getServer().getPluginManager().getPlugin(dependencyName);
        if (dependency != null && dependency.isEnabled()) enabled.accept(dependency);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        Plugin dependency = event.getPlugin();
        if (started && dependencyName.equalsIgnoreCase(dependency.getName()) && dependency.isEnabled()) {
            enabled.accept(dependency);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin dependency = event.getPlugin();
        if (started && dependencyName.equalsIgnoreCase(dependency.getName())) disabled.accept(dependency);
    }

    @Override
    public void close() {
        if (!started) return;
        started = false;
        HandlerList.unregisterAll(this);
    }
}
