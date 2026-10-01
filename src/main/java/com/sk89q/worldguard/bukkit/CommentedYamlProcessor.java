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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restores default setting descriptions after SnakeYAML rewrites a config.
 */
final class CommentedYamlProcessor extends YAMLProcessor {

    private static final Pattern KEY_PATTERN = Pattern.compile("^(\\s*)([^#\\s][^:]*):(.*)$");

    private final File file;
    private final Supplier<InputStream> defaults;
    private final Logger logger;

    CommentedYamlProcessor(File file, Supplier<InputStream> defaults, Logger logger) {
        super(file, true, YAMLFormat.EXTENDED);
        this.file = file;
        this.defaults = defaults;
        this.logger = logger;
    }

    @Override
    public boolean save() {
        if (!super.save()) {
            return false;
        }
        try (InputStream input = defaults.get()) {
            if (input == null) {
                throw new IOException("Default configuration resource is missing");
            }
            restoreComments(input);
            return true;
        } catch (IOException e) {
            logger.log(Level.WARNING, "@wglog:logFailedToRestoreConfigurationCommentsIn@" + file, e);
            return false;
        }
    }

    private void restoreComments(InputStream input) throws IOException {
        Map<String, List<String>> comments = collectComments(readLines(input));
        List<String> saved = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        Path target = file.toPath();
        Path temporary = Files.createTempFile(target.getParent(), file.getName(), ".comments");
        try {
            Files.write(temporary, applyComments(saved, comments), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<String> readLines(InputStream input) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Map<String, List<String>> collectComments(List<String> lines) {
        Map<String, List<String>> comments = new HashMap<>();
        List<PathPart> parents = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ParsedKey key = parseKey(lines.get(i));
            if (key == null) {
                continue;
            }
            String path = updatePath(parents, key);
            List<String> description = new ArrayList<>();
            for (int j = i - 1; j >= 0; j--) {
                String candidate = lines.get(j).trim();
                if (!candidate.startsWith("#")) {
                    break;
                }
                description.add(0, candidate);
            }
            if (!description.isEmpty()) {
                comments.put(path, description);
            }
        }
        return comments;
    }

    private static List<String> applyComments(List<String> lines, Map<String, List<String>> comments) {
        List<String> result = new ArrayList<>();
        List<PathPart> parents = new ArrayList<>();
        for (String line : lines) {
            ParsedKey key = parseKey(line);
            if (key != null) {
                List<String> description = comments.get(updatePath(parents, key));
                if (description != null) {
                    String indent = " ".repeat(key.indent);
                    for (String comment : description) {
                        result.add(indent + comment);
                    }
                }
            }
            result.add(line);
        }
        return result;
    }

    private static ParsedKey parseKey(String line) {
        Matcher matcher = KEY_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        return new ParsedKey(matcher.group(1).length(), matcher.group(2).trim());
    }

    private static String updatePath(List<PathPart> parents, ParsedKey key) {
        while (!parents.isEmpty() && parents.get(parents.size() - 1).indent >= key.indent) {
            parents.remove(parents.size() - 1);
        }
        parents.add(new PathPart(key.indent, key.name));
        StringBuilder path = new StringBuilder();
        for (PathPart part : parents) {
            if (path.length() > 0) {
                path.append('.');
            }
            path.append(part.name);
        }
        return path.toString();
    }

    private static final class ParsedKey {
        public final int indent;
        public final String name;

        private ParsedKey(int indent, String name) {
            this.indent = indent;
            this.name = name;
        }
    }

    private static final class PathPart {
        public final int indent;
        public final String name;

        private PathPart(int indent, String name) {
            this.indent = indent;
            this.name = name;
        }
    }
}
