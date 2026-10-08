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

package com.sk89q.worldguard.commands;

import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.serializer.legacy.LegacyComponentSerializer;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * Command-related utility methods.
 */
public final class CommandUtils {

    private static final char[] COLOR_MACROS = createColorMacros();

    private CommandUtils() {
    }

    /**
     * Replace color macros in a string.
     *
     * @param str the string
     * @return the new string
     */
    @SuppressWarnings("deprecation")
    public static String replaceColorMacros(String str) {
        str = expandColorMacros(str);
        String[] lines = str.split("\n");
        StringBuilder serialized = new StringBuilder(str.length());
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            TextComponent comp = LegacyComponentSerializer.INSTANCE.deserialize(line, '&');
            if (index > 0) {
                serialized.append('\n');
            }
            serialized.append(LegacyComponentSerializer.INSTANCE.serialize(comp));
        }
        str = serialized.toString();

        return str;
    }

    private static String expandColorMacros(String input) {
        StringBuilder output = null;
        int copiedUntil = 0;
        for (int index = 0; index + 1 < input.length(); index++) {
            if (input.charAt(index) != '`') continue;
            char macro = input.charAt(index + 1);
            char replacement = macro < COLOR_MACROS.length ? COLOR_MACROS[macro] : 0;
            if (replacement == 0) continue;
            if (output == null) output = new StringBuilder(input.length());
            output.append(input, copiedUntil, index).append('&').append(replacement);
            copiedUntil = ++index + 1;
        }
        return output == null ? input : output.append(input, copiedUntil, input.length()).toString();
    }

    private static char[] createColorMacros() {
        char[] macros = new char[128];
        macros['r'] = 'c';
        macros['R'] = '4';
        macros['y'] = 'e';
        macros['Y'] = '6';
        macros['g'] = 'a';
        macros['G'] = '2';
        macros['c'] = 'b';
        macros['C'] = '3';
        macros['b'] = '9';
        macros['B'] = '1';
        macros['p'] = 'd';
        macros['P'] = '5';
        macros['0'] = '0';
        macros['1'] = '8';
        macros['2'] = '7';
        macros['w'] = 'F';
        macros['k'] = 'k';
        macros['l'] = 'l';
        macros['m'] = 'm';
        macros['n'] = 'n';
        macros['o'] = 'o';
        macros['x'] = 'r';
        return macros;
    }


    /**
     * Get the name of the given owner object.
     *
     * @param owner the owner object
     * @return a name
     */
    public static String getOwnerName(@Nullable Object owner) {
        if (owner == null) {
            return "?";
        } else if (owner instanceof Actor) {
            return ((Actor) owner).getName();
        } else {
            return "?";
        }
    }

    /**
     * Return a function that accepts a string to send a message to the
     * given sender.
     *
     * @param sender the sender
     * @return a function
     */
    @SuppressWarnings("deprecation")
    public static Function<String, ?> messageFunction(final Actor sender) {
        return s -> {
            sender.printRaw(s);
            return null;
        };
    }

    /**
     * Return a function that accepts a TextComponent to send a message to the
     * given sender.
     *
     * @param sender the sender
     * @return a function
     */
    public static Function<TextComponent, ?> messageComponentFunction(final Actor sender) {
        return s -> {
            sender.print(s);
            return null;
        };
    }

}
