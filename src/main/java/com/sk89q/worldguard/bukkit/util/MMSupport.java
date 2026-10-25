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

package com.sk89q.worldguard.bukkit.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.command.CommandSender;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and sends Adventure components using MiniMessage exclusively.
 */
public final class MMSupport {

    private static final int CACHE_SIZE = 8192;
    private static final String COMPONENT_TAG = "wg_component";
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.resolver(TagResolver.standard(),
                    TagResolver.resolver(COMPONENT_TAG, (arguments, context) -> {
                        String encoded = arguments.popOr("Missing component data").value();
                        try {
                            String json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                            return Tag.selfClosingInserting(GSON.deserialize(json));
                        } catch (IllegalArgumentException e) {
                            throw context.newException("Invalid component data", e, arguments);
                        }
                    })))
            .build();
    private static final Component EMPTY_COMPONENT = Component.empty()
            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    private static final List<Component> EMPTY_COMPONENTS = List.of();
    private static final Map<String, Component> COMPONENT_CACHE = new ConcurrentHashMap<>(CACHE_SIZE);

    private MMSupport() {
    }

    public static Component component(String text) {
        if (text == null || text.isEmpty()) return EMPTY_COMPONENT;
        Component cached = COMPONENT_CACHE.get(text);
        if (cached != null) return cached;
        if (COMPONENT_CACHE.size() >= CACHE_SIZE) COMPONENT_CACHE.clear();
        return COMPONENT_CACHE.computeIfAbsent(text,
                value -> withoutDefaultItalic(MINI_MESSAGE.deserialize(value)));
    }

    public static Component component(String text, TagResolver resolver) {
        return text == null || text.isEmpty() ? EMPTY_COMPONENT
                : withoutDefaultItalic(MINI_MESSAGE.deserialize(text, resolver));
    }

    public static String miniMessage(com.sk89q.worldedit.util.formatting.text.Component component) {
        if (component == null) return "";
        String json = com.sk89q.worldedit.util.formatting.text.serializer.gson.GsonComponentSerializer.INSTANCE
                .serialize(component);
        Component adventure = GSON.deserialize(json);
        return MINI_MESSAGE.serialize(withoutDefaultItalic(adventure));
    }

    public static String encodeComponent(Component component) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(GSON.serialize(component).getBytes(StandardCharsets.UTF_8));
    }

    public static String componentTag(String encoded) {
        return "<" + COMPONENT_TAG + ":'" + encoded + "'>";
    }

    public static String escape(String text) {
        if (text == null) return "";
        return text.indexOf('<') < 0 && text.indexOf('\\') < 0 ? text : MINI_MESSAGE.escapeTags(text);
    }

    public static List<Component> components(Collection<String> lines) {
        if (lines == null || lines.isEmpty()) return EMPTY_COMPONENTS;
        List<Component> components = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line != null && !line.isEmpty()) components.add(component(line));
        }
        if (components.isEmpty()) return EMPTY_COMPONENTS;
        return Collections.unmodifiableList(components);
    }

    public static void send(CommandSender sender, Collection<String> lines) {
        if (sender == null || lines == null || lines.isEmpty()) return;
        for (String line : lines) {
            if (line != null && !line.isEmpty()) sender.sendMessage(component(line));
        }
    }

    private static Component withoutDefaultItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
