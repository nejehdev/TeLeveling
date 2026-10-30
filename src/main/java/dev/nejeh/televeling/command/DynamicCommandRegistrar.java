package dev.nejeh.televeling.command;

import dev.nejeh.televeling.TeLevelingPlugin;
import dev.nejeh.televeling.level.LevelDefinition;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DynamicCommandRegistrar {
    private final TeLevelingPlugin plugin;
    private final List<Command> registered = new ArrayList<>();
    private CommandMap commandMap;

    public DynamicCommandRegistrar(TeLevelingPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        resolveCommandMap();
        unregisterAll();

        for (String alias : plugin.getConfig().getStringList("aliases")) {
            String normalized = alias.trim().toLowerCase(java.util.Locale.ROOT);
            if (!normalized.matches("[a-z0-9_-]{1,64}")) {
                plugin.getLogger().warning("Ignoring invalid TeLeveling alias '" + alias + "'.");
                continue;
            }
            if (commandMap.getCommand(normalized) != null) {
                plugin.getLogger().warning("Could not register TeLeveling alias '/" + normalized
                        + "' because it is already registered.");
                continue;
            }
            DynamicRootAliasCommand command = new DynamicRootAliasCommand(plugin, normalized);
            if (commandMap.register("televeling", command)) {
                registered.add(command);
            }
        }

        for (LevelDefinition definition : plugin.levels().all().values()) {
            if (definition.commandAliases().isEmpty()) {
                plugin.getLogger().warning("No command-alias configured for level '" + definition.id() + "'.");
                continue;
            }
            String conflict = definition.commandAliases().stream()
                    .filter(alias -> commandMap.getCommand(alias) != null)
                    .findFirst()
                    .orElse(null);
            if (conflict != null) {
                plugin.getLogger().warning("Could not register level command because '/" + conflict
                        + "' is already registered.");
                continue;
            }
            for (String alias : definition.commandAliases()) {
                if (alias.equalsIgnoreCase("televeling")
                        || plugin.getConfig().getStringList("aliases").stream()
                        .anyMatch(rootAlias -> rootAlias.equalsIgnoreCase(alias))) {
                    plugin.getLogger().warning("Level command alias '" + alias
                            + "' conflicts with a TeLeveling command alias.");
                    conflict = alias;
                    break;
                }
            }
            if (conflict != null) {
                continue;
            }
            DynamicLevelCommand command = new DynamicLevelCommand(plugin, definition);
            if (!commandMap.register("televeling", command)) {
                plugin.getLogger().warning("Could not register level command: " + definition.commandAliases().getFirst());
                continue;
            }
            registered.add(command);
        }
    }

    private void resolveCommandMap() {
        if (commandMap != null) {
            return;
        }
        try {
            Method method = plugin.getServer().getClass().getMethod("getCommandMap");
            commandMap = (CommandMap) method.invoke(plugin.getServer());
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassCastException exception) {
            throw new IllegalStateException("Paper did not expose its Bukkit command map.", exception);
        }
    }

    public void unregisterAll() {
        if (commandMap == null) {
            return;
        }
        for (Command command : registered) {
            removeCommandMappings(command);
            command.unregister(commandMap);
        }
        registered.clear();
    }

    private void removeCommandMappings(Command command) {
        Field field = findKnownCommandsField(commandMap.getClass());
        try {
            Object value = field.get(commandMap);
            if (!(value instanceof Map<?, ?> knownCommands)) {
                throw new IllegalStateException("Paper's command map has no known-commands map.");
            }
            removeCommandMappings(knownCommands, command);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not remove TeLeveling command mappings.", exception);
        }
    }

    static void removeCommandMappings(Map<?, ?> knownCommands, Command command) {
        knownCommands.keySet().removeIf(label -> knownCommands.get(label) == command);
    }

    private static Field findKnownCommandsField(Class<?> commandMapType) {
        for (Class<?> type = commandMapType; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("knownCommands");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException exception) {
                // Continue through the command-map implementation's superclass hierarchy.
            }
        }
        throw new IllegalStateException("Paper's command map does not expose its known-commands map.");
    }
}
