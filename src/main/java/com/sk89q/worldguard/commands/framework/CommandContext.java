/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) WorldGuard team and contributors
 */

package com.sk89q.worldguard.commands.framework;

import com.sk89q.worldguard.bukkit.BukkitMessages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CommandContext {
    public final String rawCommand;
    private final List<String> arguments;
    private final Set<Character> booleanFlags;
    private final Map<Character, String> valueFlags;

    public CommandContext(String[] input, String flagSpecification) throws CommandException {
        this(input, flagSpecification, String.join(" ", input));
    }

    public CommandContext(String[] input, String flagSpecification, String rawCommand) throws CommandException {
        this.rawCommand = rawCommand;
        FlagRules rules = parseFlagRules(flagSpecification);
        ParsedInput parsed = parseInput(input, rules);
        arguments = Collections.unmodifiableList(parsed.arguments);
        booleanFlags = Collections.unmodifiableSet(parsed.booleanFlags);
        valueFlags = Collections.unmodifiableMap(parsed.valueFlags);
    }

    private static FlagRules parseFlagRules(String specification) {
        Set<Character> accepted = new HashSet<>();
        Set<Character> valued = new HashSet<>();
        for (int i = 0; i < specification.length(); i++) {
            char flag = specification.charAt(i);
            if (flag == ':') {
                continue;
            }
            accepted.add(flag);
            if (i + 1 < specification.length() && specification.charAt(i + 1) == ':') {
                valued.add(flag);
            }
        }
        return new FlagRules(accepted, valued);
    }

    private static ParsedInput parseInput(String[] input, FlagRules rules) throws CommandException {
        List<String> parsedArguments = new ArrayList<>();
        Set<Character> parsedBooleanFlags = new HashSet<>();
        Map<Character, String> parsedValueFlags = new HashMap<>();
        boolean positionalOnly = false;

        for (int index = 0; index < input.length; index++) {
            String token = input[index];
            if (!positionalOnly && token.equals("--")) {
                positionalOnly = true;
                continue;
            }
            if (!positionalOnly && isFlagToken(token)) {
                index = parseFlagToken(
                        input, index, token.substring(1), rules,
                        parsedBooleanFlags, parsedValueFlags);
            } else {
                parsedArguments.add(token);
            }
        }

        return new ParsedInput(parsedArguments, parsedBooleanFlags, parsedValueFlags);
    }

    private static boolean isFlagToken(String token) {
        return token.length() > 1 && token.charAt(0) == '-' && Character.isLetter(token.charAt(1));
    }

    private static int parseFlagToken(
            String[] input,
            int inputIndex,
            String packed,
            FlagRules rules,
            Set<Character> parsedBooleanFlags,
            Map<Character, String> parsedValueFlags) throws CommandException {
        for (int flagIndex = 0; flagIndex < packed.length(); flagIndex++) {
            char flag = packed.charAt(flagIndex);
            requireAcceptedFlag(rules, flag);
            if (rules.valued.contains(flag)) {
                ParsedFlagValue parsed = parseFlagValue(input, inputIndex, packed, flagIndex, flag);
                parsedValueFlags.put(flag, parsed.value);
                return parsed.inputIndex;
            }
            parsedBooleanFlags.add(flag);
        }
        return inputIndex;
    }

    private static void requireAcceptedFlag(FlagRules rules, char flag) throws CommandException {
        if (!rules.accepted.contains(flag)) {
            throw new CommandException(BukkitMessages.template(
                    "commandUnknownFlag", "flag", "-" + flag));
        }
    }

    private static ParsedFlagValue parseFlagValue(
            String[] input, int inputIndex, String packed, int flagIndex, char flag)
            throws CommandException {
        if (flagIndex + 1 < packed.length()) {
            String value = packed.substring(flagIndex + 1);
            return new ParsedFlagValue(value.startsWith("=") ? value.substring(1) : value, inputIndex);
        }
        int valueIndex = inputIndex + 1;
        if (valueIndex < input.length) {
            return new ParsedFlagValue(input[valueIndex], valueIndex);
        }
        throw new CommandException(BukkitMessages.template(
                "commandFlagValueRequired", "flag", "-" + flag));
    }

    private static final class FlagRules {
        public final Set<Character> accepted;
        public final Set<Character> valued;

        private FlagRules(Set<Character> accepted, Set<Character> valued) {
            this.accepted = accepted;
            this.valued = valued;
        }
    }

    private static final class ParsedInput {
        public final List<String> arguments;
        public final Set<Character> booleanFlags;
        public final Map<Character, String> valueFlags;

        private ParsedInput(
                List<String> arguments,
                Set<Character> booleanFlags,
                Map<Character, String> valueFlags) {
            this.arguments = arguments;
            this.booleanFlags = booleanFlags;
            this.valueFlags = valueFlags;
        }
    }

    private static final class ParsedFlagValue {
        public final String value;
        public final int inputIndex;

        private ParsedFlagValue(String value, int inputIndex) {
            this.value = value;
            this.inputIndex = inputIndex;
        }
    }

    public String getString(int index) {
        return arguments.get(index);
    }

    public String getString(int index, String defaultValue) {
        return index < arguments.size() ? arguments.get(index) : defaultValue;
    }

    public String getJoinedStrings(int initialIndex) {
        return String.join(" ", arguments.subList(initialIndex, arguments.size()));
    }

    public int getInteger(int index) {
        return Integer.parseInt(getString(index));
    }

    public int getInteger(int index, int defaultValue) {
        return index < arguments.size() ? getInteger(index) : defaultValue;
    }

    public String[] getSlice(int index) {
        return arguments.subList(Math.min(index, arguments.size()), arguments.size()).toArray(String[]::new);
    }

    public String[] getParsedPaddedSlice(int index, int padding) {
        String[] result = new String[Math.max(0, arguments.size() - index) + padding];
        for (int i = index; i < arguments.size(); i++) {
            result[i - index + padding] = arguments.get(i);
        }
        return result;
    }

    public boolean hasFlag(char flag) {
        return booleanFlags.contains(flag) || valueFlags.containsKey(flag);
    }

    public String getFlag(char flag) {
        return valueFlags.get(flag);
    }

    public int getFlagInteger(char flag) {
        return Integer.parseInt(getFlag(flag));
    }

    public int argsLength() {
        return arguments.size();
    }
}
