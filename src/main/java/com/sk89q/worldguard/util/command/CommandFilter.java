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

package com.sk89q.worldguard.util.command;

import com.google.common.base.Predicate;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Checks whether a command is permitted with support for subcommands
 * split by {@code \s} (regular expressions).
 *
 * <p>{@code permitted} always overrides {@code denied} (unlike other parts of
 * WorldGuard). Either can be null. If both are null, then every command is
 * permitted. If only {@code permitted} is null, then all commands but
 * those in the list of denied are permitted. If only {@code denied} is null,
 * then only commands in the list of permitted are permitted. If neither are
 * null, only permitted commands are permitted and the list of denied commands
 * is not used.</p>
 *
 * <p>The test is case in-sensitive.</p>
 */
public class CommandFilter implements Predicate<String> {

    @Nullable
    private final Collection<String> permitted;
    @Nullable
    private final Collection<String> denied;

    /**
     * Create a new instance.
     *
     * @param permitted a list of rules for permitted commands
     * @param denied a list of rules for denied commands
     */
    public CommandFilter(@Nullable Collection<String> permitted, @Nullable Collection<String> denied) {
        this.permitted = normalizeRules(permitted);
        this.denied = normalizeRules(denied);
    }

    @Override
    public boolean apply(String command) {
        String normalized = normalize(command);
        if (permitted != null) {
            for (String rule : permitted) {
                if (matches(normalized, rule)) {
                    return true;
                }
            }
            return false;
        }
        if (denied != null) {
            for (String rule : denied) {
                if (matches(normalized, rule)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Nullable
    private static Collection<String> normalizeRules(@Nullable Collection<String> rules) {
        if (rules == null) {
            return null;
        }
        List<String> normalized = new ArrayList<>(rules.size());
        for (String rule : rules) {
            normalized.add(normalize(rule));
        }
        return List.copyOf(normalized);
    }

    private static boolean matches(String command, String rule) {
        return command.equals(rule)
                || command.length() > rule.length()
                && command.startsWith(rule)
                && command.charAt(rule.length()) == ' ';
    }

    private static String normalize(String command) {
        String stripped = command.strip();
        StringBuilder output = new StringBuilder(stripped.length());
        boolean pendingSpace = false;
        for (int i = 0; i < stripped.length(); i++) {
            char character = stripped.charAt(i);
            if (Character.isWhitespace(character)) {
                pendingSpace = output.length() > 0;
            } else {
                if (pendingSpace) {
                    output.append(' ');
                    pendingSpace = false;
                }
                output.append(Character.toLowerCase(character));
            }
        }
        int space = output.indexOf(" ");
        int colon = output.indexOf(":");
        if (output.length() > 0 && output.charAt(0) == '/'
                && colon > 0 && (space < 0 || colon < space)) {
            output.delete(1, colon + 1);
        }
        return output.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Builder class for {@code CommandFilter}.
     *
     * <p>If {@link #permit(String...)} is never called, then the
     * permitted rule list will be {@code null}. Likewise if
     * {@link #deny(String...)} is never called.</p>
     */
    public static class Builder {
        private Set<String> permitted;
        private Set<String> denied;

        /**
         * Create a new instance.
         */
        public Builder() {
        }

        /**
         * Permit the given list of commands.
         *
         * @param rules list of commands
         * @return the builder object
         */
        public Builder permit(String ... rules) {
            checkNotNull(rules);
            if (permitted == null) {
                permitted = new HashSet<>();
            }
            permitted.addAll(Arrays.asList(rules));
            return this;
        }

        /**
         * Deny the given list of commands.
         *
         * @param rules list of commands
         * @return the builder object
         */
        public Builder deny(String ... rules) {
            checkNotNull(rules);
            if (denied == null) {
                denied = new HashSet<>();
            }
            denied.addAll(Arrays.asList(rules));
            return this;
        }

        /**
         * Create a command filter.
         *
         * @return a new command filter
         */
        public CommandFilter build() {
            return new CommandFilter(
                    permitted != null ? new HashSet<>(permitted) : null,
                    denied != null ? new HashSet<>(denied) : null);
        }
    }

}
