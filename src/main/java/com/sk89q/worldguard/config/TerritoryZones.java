package com.sk89q.worldguard.config;

import com.sk89q.util.yaml.YAMLNode;
import com.sk89q.util.yaml.YAMLProcessor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class TerritoryZones {

    private final Map<String, RegionFilter> worlds;
    private final RegionFilter fallback;

    private TerritoryZones(List<Rule> rules, boolean hasWhitelist) {
        Set<String> names = new HashSet<>();
        for (Rule rule : rules) names.addAll(rule.worlds);
        names.remove("*");
        Map<String, RegionFilter> filters = new HashMap<>(names.size());
        for (String world : names) filters.put(world, compile(rules, hasWhitelist, world));
        worlds = Map.copyOf(filters);
        fallback = compile(rules, hasWhitelist, "*");
    }

    static TerritoryZones read(YAMLProcessor config, Set<String> legacyWorlds, Set<String> legacyExcluded) {
        Object value = config.getProperty("settings.zones");
        if (value == null) {
            return new TerritoryZones(List.of(new Rule(true, legacyWorlds, Set.of("*")),
                    new Rule(false, legacyExcluded, Set.of("*"))), true);
        }
        Map<?, ?> zones = map(value, "settings.zones");
        List<Rule> rules = new ArrayList<>();
        boolean hasWhitelist = false;
        for (Map.Entry<?, ?> entry : zones.entrySet()) {
            String path = "settings.zones." + entry.getKey();
            Map<?, ?> zone = map(entry.getValue(), path);
            boolean whitelist = switch (String.valueOf(zone.get("type")).toLowerCase(Locale.ROOT)) {
                case "whitelist" -> true;
                case "blacklist" -> false;
                default -> throw new IllegalArgumentException("Invalid zone type: " + path + ".type");
            };
            hasWhitelist |= whitelist;
            if (!(zone.get("list") instanceof Collection<?> entries)) {
                throw new IllegalArgumentException("Expected zone list: " + path + ".list");
            }
            int index = 0;
            for (Object item : entries) {
                String itemPath = path + ".list[" + index++ + "]";
                Map<?, ?> selection = map(item, itemPath);
                rules.add(new Rule(whitelist, names(selection.get("worlds"), itemPath + ".worlds", false),
                        names(selection.get("regions"), itemPath + ".regions", true)));
            }
        }
        return new TerritoryZones(rules, hasWhitelist);
    }

    RegionFilter forWorld(String world) {
        return worlds.getOrDefault(world, fallback);
    }

    private static RegionFilter compile(List<Rule> rules, boolean hasWhitelist, String world) {
        Set<String> allowed = new HashSet<>();
        Set<String> denied = new HashSet<>();
        boolean allowAll = !hasWhitelist;
        for (Rule rule : rules) {
            if (!rule.worlds.contains("*") && !rule.worlds.contains(world)) continue;
            if (rule.regions.contains("*")) {
                if (!rule.whitelist) return RegionFilter.DENIED;
                allowAll = true;
            } else {
                (rule.whitelist ? allowed : denied).addAll(rule.regions);
            }
        }
        if (!allowAll) allowed.removeAll(denied);
        if (!allowAll && allowed.isEmpty()) return RegionFilter.DENIED;
        if (allowAll && denied.isEmpty()) return RegionFilter.ALLOWED;
        return new RegionFilter(true, true, allowAll, Set.copyOf(allowed), Set.copyOf(denied));
    }

    private static Map<?, ?> map(Object value, String path) {
        if (value instanceof YAMLNode node) return node.getMap();
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Expected zone section: " + path);
    }

    private static Set<String> names(Object value, String path, boolean regions) {
        Collection<?> values;
        if (value instanceof String string) values = List.of(string);
        else if (value instanceof Collection<?> collection) values = collection;
        else throw new IllegalArgumentException("Expected string or list: " + path);
        Set<String> result = new HashSet<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("Expected non-empty name: " + path);
            }
            result.add(regions ? name.toLowerCase(Locale.ROOT) : name);
        }
        return Set.copyOf(result);
    }

    static final class RegionFilter {
        private static final RegionFilter ALLOWED = new RegionFilter(true, false, true, Set.of(), Set.of());
        private static final RegionFilter DENIED = new RegionFilter(false, false, false, Set.of(), Set.of());

        public final boolean enabled;
        public final boolean requiresRegion;
        private final boolean allowAll;
        private final Set<String> allowed;
        private final Set<String> denied;

        private RegionFilter(boolean enabled, boolean requiresRegion, boolean allowAll,
                             Set<String> allowed, Set<String> denied) {
            this.enabled = enabled;
            this.requiresRegion = requiresRegion;
            this.allowAll = allowAll;
            this.allowed = allowed;
            this.denied = denied;
        }

        boolean allows(String region) {
            return enabled && !denied.contains(region) && (allowAll || allowed.contains(region));
        }
    }

    private static final class Rule {
        public final boolean whitelist;
        public final Set<String> worlds;
        public final Set<String> regions;

        private Rule(boolean whitelist, Set<String> worlds, Set<String> regions) {
            this.whitelist = whitelist;
            this.worlds = worlds;
            this.regions = regions;
        }
    }
}
