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

import com.sk89q.util.yaml.YAMLFormat;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldguard.bukkit.util.MMSupport;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and applies all configurable player-facing messages.
 */
public final class BukkitMessages {

    private static final Pattern PLACEHOLDER_MARKER =
            Pattern.compile("@wgarg:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]*)@");
    private static final Pattern MESSAGE_TOKEN = Pattern.compile("@wg:([A-Za-z0-9]+)@");

    private final WorldGuardPlugin plugin;
    private final File file;
    private final Map<Player, Map<String, Long>> cooldowns = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile Map<String, Replacement> replacements = Map.of();
    private volatile String prefix = "";
    private volatile int actionBarMultipleLinesMode = 2;

    public BukkitMessages(WorldGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
    }

    BukkitMessages(Map<String, List<String>> messages, String prefix) {
        this.plugin = null;
        this.file = null;
        this.prefix = prefix;
        Map<String, Replacement> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : messages.entrySet()) {
            MessageDefinition definition =
                    new MessageDefinition(entry.getValue(), DeliveryMode.MESSAGE, -1);
            loaded.put(entry.getKey(), new Replacement(entry.getKey(), definition));
        }
        replacements = Map.copyOf(loaded);
    }

    public void load() {
        plugin.createDefaultConfiguration(file, "messages.yml");
        prefix = plugin.getLocaleManager().configuredPrefix();
        actionBarMultipleLinesMode = plugin.getLocaleManager().configuredActionBarMultipleLinesMode();
        migrateBooleanLikeKeys();

        YAMLProcessor config = new YAMLProcessor(file, false, YAMLFormat.EXTENDED);
        try {
            BukkitConfigValidator.validateNoDuplicateKeys(plugin, file);
            config.load();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "@wglog:unableLoadMessages@", e);
            replacements = Map.of();
            return;
        }
        BukkitConfigValidator.validateMessages(plugin, config, file, actionBarMultipleLinesMode);

        Map<String, MessageDefinition> configuredMessages = readDefinitions(config.getProperty("messages"));
        Map<String, Replacement> loaded = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(loadBundledKeys());
        keys.addAll(configuredMessages.keySet());
        for (String key : keys) {
            MessageDefinition definition = configuredMessages.getOrDefault(key, MessageDefinition.EMPTY);
            loaded.put(key, new Replacement(key, definition));
        }
        replacements = Map.copyOf(loaded);
        cooldowns.clear();
    }

    private Set<String> loadBundledKeys() {
        try (InputStream input = plugin.getDefaultConfigurationResource("messages.yml")) {
            if (input == null) return Set.of();
            Object rootValue = new Yaml().load(input);
            if (!(rootValue instanceof Map<?, ?> root)) return Set.of();
            Object messagesValue = root.get("messages");
            if (!(messagesValue instanceof Map<?, ?> messages)) return Set.of();

            return readDefinitions(messages).keySet();
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "@wglog:unableReadBundledMessages@", e);
            return Set.of();
        }
    }

    private static String token(String key) {
        return "@wg:" + key + "@";
    }

    private void migrateBooleanLikeKeys() {
        try {
            String contents = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String migrated = contents.replaceAll("(?m)^ {2}(yes|no):\\r?$", "  '$1':");
            if (!contents.equals(migrated)) {
                Files.writeString(file.toPath(), migrated, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "@wglog:unableMigrateBooleanKeys@", e);
        }
    }

    static List<String> readLines(Object value) {
        if (value instanceof String string) {
            return string.isEmpty() ? List.of() : List.of(string);
        }
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }

        List<String> lines = new ArrayList<>(collection.size());
        for (Object line : collection) {
            if (line instanceof String string && !string.isEmpty()) {
                lines.add(string);
            }
        }
        return List.copyOf(lines);
    }

    static List<String> readTitleLines(Object value) {
        if (value instanceof String string) {
            return string.isEmpty() ? List.of() : List.of(string);
        }
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }

        List<String> lines = new ArrayList<>(2);
        for (Object line : collection) {
            if (lines.size() == 2) break;
            lines.add(line instanceof String string ? string : "");
        }
        if (allEmpty(lines)) return List.of();
        return List.copyOf(lines);
    }

    static Map<String, List<String>> readMessages(YAMLProcessor config) {
        Map<String, List<String>> messages = new LinkedHashMap<>();
        for (Map.Entry<String, MessageDefinition> entry
                : readDefinitions(config.getProperty("messages")).entrySet()) {
            messages.put(entry.getKey(), entry.getValue().messages);
        }
        return Map.copyOf(messages);
    }

    private static Map<String, MessageDefinition> readDefinitions(Object value) {
        Map<String, MessageDefinition> definitions = new LinkedHashMap<>();
        collectDefinitions(value, "", definitions);
        return Map.copyOf(definitions);
    }

    private static void collectDefinitions(
            Object value, String key, Map<String, MessageDefinition> definitions) {
        Map<?, ?> map;
        if (value instanceof com.sk89q.util.yaml.YAMLNode node) {
            map = node.getMap();
        } else if (value instanceof Map<?, ?> valueMap) {
            map = valueMap;
        } else {
            return;
        }

        if (!key.isEmpty() && (map.containsKey("message")
                || map.containsKey("mode") || map.containsKey("cooldownMillis"))) {
            DeliveryMode mode = DeliveryMode.parse(map.get("mode"));
            Object messageValue = map.get("message");
            List<String> messages = mode == DeliveryMode.TITLE
                    ? readTitleLines(messageValue)
                    : readLines(messageValue);
            long cooldownMillis = readCooldown(map.get("cooldownMillis"));
            definitions.put(key, new MessageDefinition(messages, mode, cooldownMillis));
            return;
        }

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String child) || child.isEmpty()) {
                continue;
            }
            String childKey = key.isEmpty()
                    ? child
                    : key + Character.toUpperCase(child.charAt(0)) + child.substring(1);
            collectDefinitions(entry.getValue(), childKey, definitions);
        }
    }

    private static long readCooldown(Object value) {
        if (value instanceof Number number) {
            return Math.max(-1, number.longValue());
        }
        if (value instanceof String string) {
            try {
                return Math.max(-1, Long.parseLong(string));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    public List<String> lines(String input) {
        return render(input, false);
    }

    private List<String> render(String input, boolean preserveEmptyLines) {
        if (input == null || input.isEmpty()) return List.of();

        List<String> rendered = List.of(input);
        for (int pass = 0; pass < 16; pass++) {
            List<String> previous = rendered;
            List<String> next = new ArrayList<>(previous.size());
            for (String source : previous) {
                next.addAll(expandSource(source));
            }
            rendered = List.copyOf(next);
            if (rendered.isEmpty() || rendered.equals(previous)) break;
        }
        List<String> formatted = new ArrayList<>(rendered.size());
        for (String line : rendered) {
            String value = replacePlaceholder(line, "prefix", prefix);
            if (preserveEmptyLines || !value.isEmpty()) formatted.add(value);
        }
        return List.copyOf(formatted);
    }

    private List<String> expandSource(String source) {
        Map<String, Replacement> available = replacements;
        Matcher matcher = MESSAGE_TOKEN.matcher(source);
        List<StringBuilder> outputs = new ArrayList<>();
        outputs.add(new StringBuilder(source.length()));
        int cursor = 0;
        boolean changed = false;
        while (matcher.find()) {
            String literal = source.substring(cursor, matcher.start());
            for (StringBuilder output : outputs) output.append(literal);

            int argumentEnd = matcher.end();
            Map<String, String> placeholders = new LinkedHashMap<>();
            Matcher argumentMatcher = PLACEHOLDER_MARKER.matcher(source);
            argumentMatcher.region(argumentEnd, source.length());
            while (argumentMatcher.lookingAt()) {
                placeholders.put(argumentMatcher.group(1), decodePlaceholder(argumentMatcher.group(2)));
                argumentEnd = argumentMatcher.end();
                argumentMatcher.region(argumentEnd, source.length());
            }

            Replacement replacement = available.get(matcher.group(1));
            if (replacement == null) {
                for (StringBuilder output : outputs) output.append(matcher.group());
            } else {
                changed = true;
                List<String> choices = replacement.messages.isEmpty() ? List.of("") : replacement.messages;
                List<StringBuilder> expanded = new ArrayList<>(outputs.size() * choices.size());
                for (StringBuilder output : outputs) {
                    for (String choice : choices) {
                        String value = choice;
                        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
                            value = replacePlaceholder(value, placeholder.getKey(),
                                    MMSupport.escape(placeholder.getValue()));
                        }
                        expanded.add(new StringBuilder(output).append(value));
                    }
                }
                outputs = expanded;
            }
            cursor = argumentEnd;
            matcher.region(cursor, source.length());
        }
        if (!changed) return List.of(source);
        String tail = source.substring(cursor);
        List<String> expanded = new ArrayList<>(outputs.size());
        for (StringBuilder output : outputs) {
            String value = output.append(tail).toString();
            expanded.add(value);
        }
        return List.copyOf(expanded);
    }

    public static String template(String key, Object... placeholders) {
        if (key == null || key.isEmpty()) return "";
        if ((placeholders.length & 1) != 0) {
            throw new IllegalArgumentException("Placeholders must be provided as key/value pairs");
        }
        StringBuilder output = new StringBuilder(token(key));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        for (int i = 0; i < placeholders.length; i += 2) {
            String name = String.valueOf(placeholders[i]);
            if (!name.matches("[A-Za-z][A-Za-z0-9]*")) {
                throw new IllegalArgumentException("Invalid placeholder name: " + name);
            }
            String value = String.valueOf(placeholders[i + 1]);
            output.append("@wgarg:").append(name).append(':')
                    .append(encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))).append('@');
        }
        return output.toString();
    }

    public static String replacePlaceholder(String input, String name, Object value) {
        if (input == null || input.isEmpty() || name == null || name.isEmpty()) return input;
        String braced = "{" + name + "}";
        String percent = "%" + name + "%";
        String replacement = value == null ? "" : String.valueOf(value);
        StringBuilder output = null;
        int cursor = 0;
        while (cursor < input.length()) {
            int bracedIndex = input.indexOf(braced, cursor);
            int percentIndex = input.indexOf(percent, cursor);
            int nextIndex;
            String token;
            if (bracedIndex < 0 || percentIndex >= 0 && percentIndex < bracedIndex) {
                nextIndex = percentIndex;
                token = percent;
            } else {
                nextIndex = bracedIndex;
                token = braced;
            }
            if (nextIndex < 0) break;
            if (output == null) output = new StringBuilder(input.length() + replacement.length());
            output.append(input, cursor, nextIndex).append(replacement);
            cursor = nextIndex + token.length();
        }
        if (output == null) return input;
        return output.append(input, cursor, input.length()).toString();
    }

    public net.kyori.adventure.text.Component component(String input) {
        List<String> translated = lines(input);
        return translated.isEmpty() ? MMSupport.component("") : MMSupport.component(translated.get(0));
    }

    public void send(CommandSender sender, String message) {
        if (sender == null || message == null || message.isEmpty()) return;
        List<Replacement> deliveries = findDeliveries(message);
        boolean title = false;
        boolean actionBar = false;
        for (Replacement delivery : deliveries) {
            title |= delivery.mode == DeliveryMode.TITLE;
            actionBar |= delivery.mode == DeliveryMode.ACTION_BAR;
            if (title && actionBar) break;
        }
        List<String> lines = render(message, title);
        if (lines.isEmpty() || allEmpty(lines)) return;
        String actionBarLine = actionBar
                ? selectActionBarLine(lines, actionBarMultipleLinesMode)
                : null;
        if (actionBar && actionBarLine == null) return;
        if (sender instanceof Player player && isCoolingDown(player, deliveries)) return;

        if (title) {
            if (sender instanceof Player player) {
                net.kyori.adventure.text.Component titleLine = MMSupport.component(lines.get(0));
                net.kyori.adventure.text.Component subtitleLine = lines.size() > 1
                        ? MMSupport.component(lines.get(1))
                        : MMSupport.component("");
                player.showTitle(net.kyori.adventure.title.Title.title(titleLine, subtitleLine));
            }
            return;
        }
        if (actionBar) {
            if (sender instanceof Player player) {
                player.sendActionBar(MMSupport.component(actionBarLine));
            } else {
                MMSupport.send(sender, List.of(actionBarLine));
            }
            return;
        }
        MMSupport.send(sender, lines);
    }

    static String selectActionBarLine(List<String> lines, int mode) {
        if (lines == null || lines.isEmpty()) return null;
        if (lines.size() == 1) return lines.get(0);
        if (mode == 1) return null;
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    public void send(CommandSender sender, Component component) {
        if (component == null) return;
        send(sender, toMiniMessage(component));
    }

    static String toMiniMessage(Component component) {
        return MMSupport.miniMessage(component);
    }

    private static String decodePlaceholder(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private List<Replacement> findDeliveries(String input) {
        Map<String, Replacement> available = replacements;
        Map<String, Replacement> found = new LinkedHashMap<>();
        Matcher matcher = MESSAGE_TOKEN.matcher(input);
        while (matcher.find()) {
            Replacement replacement = available.get(matcher.group(1));
            if (replacement != null) {
                found.putIfAbsent(replacement.key, replacement);
            }
        }
        return List.copyOf(found.values());
    }

    private boolean isCoolingDown(Player player, List<Replacement> deliveries) {
        boolean hasCooldown = false;
        for (Replacement delivery : deliveries) {
            if (delivery.cooldownMillis > 0) {
                hasCooldown = true;
                break;
            }
        }
        if (!hasCooldown) return false;
        long now = System.nanoTime();
        synchronized (cooldowns) {
            Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
            for (Replacement delivery : deliveries) {
                if (delivery.cooldownMillis <= 0) continue;
                Long previous = playerCooldowns.get(delivery.key);
                long cooldownNanos = TimeUnit.MILLISECONDS.toNanos(delivery.cooldownMillis);
                if (previous != null && now - previous < cooldownNanos) return true;
            }
            for (Replacement delivery : deliveries) {
                if (delivery.cooldownMillis > 0) playerCooldowns.put(delivery.key, now);
            }
            return false;
        }
    }

    private static boolean allEmpty(List<String> lines) {
        for (String line : lines) {
            if (!line.isEmpty()) return false;
        }
        return true;
    }

    private enum DeliveryMode {
        MESSAGE,
        ACTION_BAR,
        TITLE;

        private static DeliveryMode parse(Object value) {
            if (!(value instanceof String string)) return MESSAGE;
            return switch (string.toLowerCase(java.util.Locale.ROOT)) {
                case "actionbar" -> ACTION_BAR;
                case "title" -> TITLE;
                default -> MESSAGE;
            };
        }
    }

    private static final class MessageDefinition {
        private static final MessageDefinition EMPTY = new MessageDefinition(List.of(), DeliveryMode.MESSAGE, -1);

        public final List<String> messages;
        public final DeliveryMode mode;
        public final long cooldownMillis;

        private MessageDefinition(List<String> messages, DeliveryMode mode, long cooldownMillis) {
            this.messages = messages;
            this.mode = mode;
            this.cooldownMillis = cooldownMillis;
        }
    }

    private static final class Replacement {
        public final String key;
        public final List<String> messages;
        public final DeliveryMode mode;
        public final long cooldownMillis;

        private Replacement(String key, MessageDefinition definition) {
            this.key = key;
            this.messages = definition.messages;
            this.mode = definition.mode;
            this.cooldownMillis = definition.cooldownMillis;
        }

    }

}
