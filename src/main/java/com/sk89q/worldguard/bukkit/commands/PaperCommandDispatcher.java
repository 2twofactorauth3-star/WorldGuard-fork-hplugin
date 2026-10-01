package com.sk89q.worldguard.bukkit.commands;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitMessages;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.commands.framework.CommandContext;
import com.sk89q.worldguard.commands.framework.CommandException;
import com.sk89q.worldguard.commands.framework.CommandPermissionsException;
import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.EnumFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.SetFlag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Builds native Paper Brigadier command trees without reflective dispatch.
 */
public final class PaperCommandDispatcher {

    private static final Set<String> MUTATING_REGION_COMMANDS = Set.of(
            "define", "redefine", "claim", "flag", "setpriority", "setparent",
            "remove", "load", "addmember", "addowner", "removemember", "removeowner");

    private final WorldGuardPlugin plugin;
    private final String root;
    private final Map<String, Binding> commands = new LinkedHashMap<>();
    private final Set<String> primaryAliases = new LinkedHashSet<>();

    public PaperCommandDispatcher(WorldGuardPlugin plugin, String root) {
        this.plugin = plugin;
        this.root = root;
    }

    public PaperCommandDispatcher add(
            String[] aliases,
            String usage,
            int min,
            int max,
            String flags,
            CommandExecutor executor,
            String... permissions
    ) {
        Binding binding = new Binding(
                List.of(aliases), usage, min, max, flags, executor,
                List.of(permissions), helpKey(aliases[0]));
        primaryAliases.add(aliases[0]);
        for (String alias : aliases) {
            commands.put(alias.toLowerCase(Locale.ROOT), binding);
        }
        return this;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> rootNode = literal(root)
                .executes(context -> {
                    showHelp(context.getSource());
                    return 0;
                });

        for (Map.Entry<String, Binding> entry : commands.entrySet()) {
            String alias = entry.getKey();
            Binding binding = entry.getValue();
            rootNode.then(literal(alias)
                    .executes(context -> execute(
                            context.getSource(), alias, new String[0], context.getInput()))
                    .then(argument("arguments", StringArgumentType.greedyString())
                            .suggests((context, builder) ->
                                    suggest(context.getSource(), binding, builder))
                            .executes(context -> execute(
                                    context.getSource(),
                                    alias,
                                    splitArguments(StringArgumentType.getString(context, "arguments")),
                                    context.getInput()))));
        }
        rootNode.then(argument("unknownCommand", StringArgumentType.greedyString())
                .executes(context -> {
                    if (!plugin.isOperational()) {
                        plugin.sendLocaleRequired(context.getSource().getSender());
                        return 0;
                    }
                    String command = "/" + root + " "
                            + StringArgumentType.getString(context, "unknownCommand");
                    plugin.getMessages().send(context.getSource().getSender(),
                            BukkitMessages.template("commandUnknown", "command", command));
                    return 0;
                }));
        return rootNode.build();
    }

    private void showHelp(CommandSourceStack source) {
        if (!plugin.isOperational()) {
            plugin.sendLocaleRequired(source.getSender());
            return;
        }
        plugin.getMessages().send(source.getSender(),
                BukkitMessages.template("commandHelpHeader", "command", root));
        Actor actor = plugin.wrapCommandSender(source.getSender());
        for (String alias : primaryAliases) {
            Binding binding = commands.get(alias);
            if (binding == null || binding.permissions.stream()
                    .anyMatch(permission -> actor == null || !actor.hasPermission(permission))) {
                continue;
            }
            plugin.getMessages().send(source.getSender(), BukkitMessages.template(
                    "commandHelpEntry",
                    "command", "/" + root + " " + alias,
                    "usage", binding.usage,
                    "description", "@wg:" + binding.helpKey + "@"));
        }
    }

    private String helpKey(String command) {
        if (root.equals("worldguard") && command.equals("reload")) return "helpReload";
        return switch (command) {
            case "define" -> "helpDefineRegion";
            case "redefine" -> "helpRedefineRegion";
            case "claim" -> "helpClaimRegion";
            case "select" -> "helpSelectRegion";
            case "info" -> "helpRegionInfo";
            case "list" -> "helpRegionList";
            case "flag" -> "helpSetFlags";
            case "flags" -> "helpViewFlags";
            case "setpriority" -> "helpSetPriority";
            case "setparent" -> "helpSetParent";
            case "remove" -> "helpRemoveRegion";
            case "load" -> "helpReloadRegions";
            case "save" -> "helpSaveRegions";
            case "teleport" -> "helpTeleport";
            case "toggle-bypass" -> "helpBypass";
            case "addmember" -> "helpAddMember";
            case "addowner" -> "helpAddOwner";
            case "removemember" -> "helpRemoveMember";
            case "removeowner" -> "helpRemoveOwner";
            default -> "commandUnknown";
        };
    }

    private int execute(CommandSourceStack source, String alias, String[] input, String rawInput) {
        if (!plugin.isOperational()) {
            plugin.sendLocaleRequired(source.getSender());
            return 0;
        }
        Binding binding = commands.get(alias);
        Actor actor = plugin.wrapCommandSender(source.getSender());
        if (actor == null) {
            plugin.getMessages().send(source.getSender(), "@wg:commandFailed@");
            return 0;
        }
        if (binding.permissions.stream().anyMatch(permission -> !actor.hasPermission(permission))) {
            plugin.getMessages().send(source.getSender(), "@wg:permissionDenied@");
            return 0;
        }

        try {
            String rawCommand = rawInput.startsWith("/") ? rawInput : "/" + rawInput;
            CommandContext context = new CommandContext(input, binding.flags, rawCommand);
            if (context.argsLength() < binding.min
                    || binding.max >= 0 && context.argsLength() > binding.max) {
                plugin.getMessages().send(source.getSender(), BukkitMessages.template(
                        "commandUsage", "usage",
                        "/" + root + " " + binding.aliases.get(0) + " " + binding.usage));
                return 0;
            }
            binding.executor.execute(context, actor);
            if (root.equals("region")
                    && MUTATING_REGION_COMMANDS.contains(binding.aliases.get(0))) {
                WorldGuard.getInstance().getPlatform().getRegionContainer().invalidateCache();
                WorldGuard.getInstance().getPlatform().getSessionManager().resetAllStates();
            }
            return 1;
        } catch (CommandException e) {
            plugin.getMessages().send(source.getSender(), e.getMessage());
            return 0;
        } catch (RuntimeException e) {
            handleFailure(source, e);
            return 0;
        }
    }

    private void handleFailure(CommandSourceStack source, Throwable failure) {
        if (failure instanceof CommandPermissionsException) {
            plugin.getMessages().send(source.getSender(), "@wg:permissionDenied@");
        } else if (failure instanceof CommandException commandException) {
            plugin.getMessages().send(source.getSender(), commandException.getMessage());
        } else {
            plugin.getLogger().log(Level.SEVERE, "@wglog:commandExecutionFailed@" + root, failure);
            plugin.getMessages().send(source.getSender(), "@wg:commandFailed@");
        }
    }

    private CompletableFuture<Suggestions> suggest(
            CommandSourceStack source, Binding binding, SuggestionsBuilder builder) {
        if (!plugin.isOperational()) {
            return builder.buildFuture();
        }
        SuggestionInput input = parseSuggestionInput(builder);

        if (input.current.startsWith("-")) {
            acceptedFlags(binding.flags).forEach(flag ->
                    suggest(input.target, input.current, "-" + flag));
        } else {
            String pendingFlag = pendingValueFlag(input.completed, binding.flags);
            if (pendingFlag != null) {
                addFlagValueSuggestions(input.target, input.current, pendingFlag);
            } else {
                addPositionalSuggestions(source, binding, input);
            }
        }
        return input.target.buildFuture();
    }

    private SuggestionInput parseSuggestionInput(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int separator = Math.max(remaining.lastIndexOf(' '), remaining.lastIndexOf('\t'));
        String completedInput = separator < 0 ? "" : remaining.substring(0, separator + 1);
        String current = separator < 0 ? remaining : remaining.substring(separator + 1);
        List<String> completed = List.of(splitArguments(completedInput));
        SuggestionsBuilder target = builder.createOffset(builder.getStart() + separator + 1);
        return new SuggestionInput(completed, current, target);
    }

    private void addFlagValueSuggestions(
            SuggestionsBuilder target, String current, String pendingFlag) {
        Collection<String> values = switch (pendingFlag) {
            case "w" -> Bukkit.getWorlds().stream().map(World::getName).toList();
            case "p" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            case "g" -> List.of("members", "nonmembers", "owners", "nonowners", "everyone");
            default -> List.of();
        };
        values.forEach(value -> suggest(target, current, value));
    }

    private void addPositionalSuggestions(
            CommandSourceStack source, Binding binding, SuggestionInput input) {
        List<String> positional = positionalArguments(input.completed, binding.flags);
        int index = positional.size();
        String command = binding.aliases.get(0);
        Collection<String> values = positionalSuggestionValues(
                source, input.completed, positional, command, index);
        values.forEach(value -> suggest(input.target, input.current, value));
        if (isMemberCommand(command) && index >= 1) {
            suggest(input.target, input.current, "g:");
        }
    }

    private Collection<String> positionalSuggestionValues(
            CommandSourceStack source,
            List<String> completed,
            List<String> positional,
            String command,
            int index) {
        if (command.equals("list") && index == 0) {
            return List.of("my", "global");
        }
        if (command.equals("toggle-bypass") && index == 0) {
            return List.of("on", "off");
        }
        if ((command.equals("load") || command.equals("save")) && index == 0) {
            return Bukkit.getWorlds().stream().map(World::getName).toList();
        }
        if (isRegionArgument(command, index)) {
            return regionIds(source, completed);
        }
        if (command.equals("flag") && index == 1) {
            return WorldGuard.getInstance().getFlagRegistry().getAll().stream()
                    .map(Flag::getName).toList();
        }
        if (command.equals("flag") && index >= 2 && positional.size() >= 2) {
            return flagValues(positional.get(1));
        }
        if (isMemberCommand(command) && index >= 1) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    private Collection<String> regionIds(CommandSourceStack source, List<String> arguments) {
        World world = source.getLocation().getWorld();
        for (int i = 0; i + 1 < arguments.size(); i++) {
            if (arguments.get(i).equals("-w")) {
                World selected = Bukkit.getWorld(arguments.get(i + 1));
                if (selected != null) world = selected;
            }
        }
        RegionManager manager = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().get(BukkitAdapter.adapt(world));
        return manager == null ? List.of() : manager.getRegions().keySet();
    }

    private static Collection<String> flagValues(String flagName) {
        Flag<?> flag = WorldGuard.getInstance().getFlagRegistry().get(flagName);
        if (flag instanceof SetFlag<?> setFlag) flag = setFlag.getType();
        if (flag instanceof StateFlag) return List.of("allow", "deny");
        if (flag instanceof BooleanFlag) return List.of("true", "false");
        if (flag instanceof EnumFlag<?> enumFlag) {
            List<String> values = new ArrayList<>();
            for (Enum<?> value : enumFlag.getEnumClass().getEnumConstants()) {
                values.add(value.name().toLowerCase(Locale.ROOT));
            }
            return values;
        }
        return List.of();
    }

    private static boolean isRegionArgument(String command, int index) {
        if (index == 0) {
            return Set.of("redefine", "select", "info", "flag", "flags", "setpriority",
                    "setparent", "remove", "teleport", "addmember", "addowner",
                    "removemember", "removeowner").contains(command);
        }
        return command.equals("setparent") && index == 1;
    }

    private static boolean isMemberCommand(String command) {
        return Set.of("addmember", "addowner", "removemember", "removeowner").contains(command);
    }

    private static Set<Character> acceptedFlags(String specification) {
        Set<Character> flags = new LinkedHashSet<>();
        for (int i = 0; i < specification.length(); i++) {
            char flag = specification.charAt(i);
            if (flag != ':') flags.add(flag);
        }
        return flags;
    }

    private static String pendingValueFlag(List<String> arguments, String specification) {
        if (arguments.isEmpty()) return null;
        String token = arguments.get(arguments.size() - 1);
        if (token.length() == 2 && token.charAt(0) == '-'
                && isValueFlag(specification, token.charAt(1))) {
            return String.valueOf(token.charAt(1));
        }
        return null;
    }

    private static List<String> positionalArguments(List<String> arguments, String specification) {
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            String token = arguments.get(i);
            if (token.length() > 1 && token.charAt(0) == '-') {
                char flag = token.charAt(1);
                if (isValueFlag(specification, flag) && token.length() == 2) i++;
            } else {
                positional.add(token);
            }
        }
        return positional;
    }

    private static boolean isValueFlag(String specification, char flag) {
        int index = specification.indexOf(flag);
        return index >= 0 && index + 1 < specification.length()
                && specification.charAt(index + 1) == ':';
    }

    private static void suggest(SuggestionsBuilder builder, String input, String value) {
        if (value.regionMatches(true, 0, input, 0, input.length())) builder.suggest(value);
    }

    private static String[] splitArguments(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < input.length(); i++) {
            char character = input.charAt(i);
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (escaped) current.append('\\');
        if (!current.isEmpty()) result.add(current.toString());
        return result.toArray(String[]::new);
    }

    @FunctionalInterface
    public interface CommandExecutor {
        void execute(CommandContext context, Actor sender) throws CommandException;
    }

    private static final class Binding {
        public final List<String> aliases;
        public final String usage;
        public final int min;
        public final int max;
        public final String flags;
        public final CommandExecutor executor;
        public final List<String> permissions;
        public final String helpKey;

        private Binding(
                List<String> aliases,
                String usage,
                int min,
                int max,
                String flags,
                CommandExecutor executor,
                List<String> permissions,
                String helpKey
        ) {
            this.aliases = aliases;
            this.usage = usage;
            this.min = min;
            this.max = max;
            this.flags = flags;
            this.executor = executor;
            this.permissions = permissions;
            this.helpKey = helpKey;
        }
    }

    private static final class SuggestionInput {
        public final List<String> completed;
        public final String current;
        public final SuggestionsBuilder target;

        private SuggestionInput(
                List<String> completed, String current, SuggestionsBuilder target) {
            this.completed = completed;
            this.current = current;
            this.target = target;
        }
    }
}
