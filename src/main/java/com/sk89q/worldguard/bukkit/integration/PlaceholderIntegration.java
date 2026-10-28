package com.sk89q.worldguard.bukkit.integration;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class PlaceholderIntegration implements AutoCloseable {

    private static final Pattern PLACEHOLDER = Pattern.compile("%[^\\s%_]+_[^%]*%");
    private static volatile Binding active;
    private final Plugin owner;
    private final Function<Plugin, Resolver> factory;
    private final PluginDependencyListener listener;
    private Binding binding;

    public PlaceholderIntegration(Plugin owner) {
        this(owner, dependency -> PlaceholderApi.create(dependency));
    }

    PlaceholderIntegration(Plugin owner, Function<Plugin, Resolver> factory) {
        this.owner = owner;
        this.factory = factory;
        listener = new PluginDependencyListener(owner, "PlaceholderAPI", this::connect, this::disconnect);
    }

    public void start() {
        listener.start();
    }

    boolean connect(Plugin dependency) {
        if (!"PlaceholderAPI".equals(dependency.getName()) || !dependency.isEnabled()) return false;
        if (binding != null && binding.plugin == dependency) return true;
        try {
            Resolver resolver = factory.apply(dependency);
            if (resolver == null) return false;
            binding = new Binding(dependency, owner, resolver);
            active = binding;
            return true;
        } catch (RuntimeException | LinkageError error) {
            owner.getLogger().log(Level.WARNING, "@wglog:placeholderIntegrationFailed@", error);
            return false;
        }
    }

    void disconnect(Plugin dependency) {
        if (binding == null || binding.plugin != dependency) return;
        if (active == binding) active = null;
        binding = null;
    }

    public static boolean containsPlaceholder(@Nullable String text) {
        return text != null && text.indexOf('%') >= 0 && PLACEHOLDER.matcher(text).find();
    }

    public static boolean available() {
        Binding current = active;
        return current != null && current.plugin.isEnabled();
    }

    public static String resolve(@Nullable CommandSender sender, String text) {
        Binding current = active;
        if (current == null || !containsPlaceholder(text) || !current.plugin.isEnabled()) return text;
        try {
            return current.resolver.resolve(sender instanceof Player player ? player : null, text);
        } catch (RuntimeException | LinkageError error) {
            if (current.failed.compareAndSet(false, true)) {
                current.owner.getLogger().log(Level.WARNING, "@wglog:placeholderIntegrationFailed@", error);
            }
            return text;
        }
    }

    @Override
    public void close() {
        listener.close();
        if (active == binding) active = null;
        binding = null;
    }

    interface Resolver {
        String resolve(@Nullable OfflinePlayer player, String text);
    }

    private static final class Binding {
        public final Plugin plugin;
        public final Plugin owner;
        public final Resolver resolver;
        public final AtomicBoolean failed = new AtomicBoolean();

        private Binding(Plugin plugin, Plugin owner, Resolver resolver) {
            this.plugin = plugin;
            this.owner = owner;
            this.resolver = resolver;
        }
    }
}
