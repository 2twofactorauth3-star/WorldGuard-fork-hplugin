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
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
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
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Component EMPTY_COMPONENT = Component.empty()
            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    private static final List<Component> EMPTY_COMPONENTS = List.of();
    private static final Map<String, Component> COMPONENT_CACHE = new ConcurrentHashMap<>(CACHE_SIZE);

    private MMSupport() {
    }

    public static Component component(String text) {
        if (text == null || text.isEmpty()) return EMPTY_COMPONENT;
        if (COMPONENT_CACHE.size() >= CACHE_SIZE) COMPONENT_CACHE.clear();
        return COMPONENT_CACHE.computeIfAbsent(text,
                value -> withoutDefaultItalic(MINI_MESSAGE.deserialize(value)));
    }

    public static String miniMessage(com.sk89q.worldedit.util.formatting.text.Component component) {
        if (component == null) return "";
        String json = com.sk89q.worldedit.util.formatting.text.serializer.gson.GsonComponentSerializer.INSTANCE
                .serialize(component);
        Component adventure = GsonComponentSerializer.gson().deserialize(json);
        return MINI_MESSAGE.serialize(withoutDefaultItalic(adventure));
    }

    public static String escape(String text) {
        return text == null ? "" : MINI_MESSAGE.escapeTags(text);
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
