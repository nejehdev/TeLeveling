package dev.nejeh.televeling.command;

import dev.nejeh.televeling.TeLevelingPlugin;
import dev.nejeh.televeling.level.LevelDefinition;
import org.bukkit.command.CommandMap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class DynamicCommandRegistrar {
    private final TeLevelingPlugin plugin;
    private final List<DynamicLevelCommand> registered = new ArrayList<>();
    private CommandMap commandMap;

    public DynamicCommandRegistrar(TeLevelingPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        resolveCommandMap();
        for (DynamicLevelCommand command : registered) {
            command.unregister(commandMap);
        }
        registered.clear();

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
        for (DynamicLevelCommand command : registered) {
            command.unregister(commandMap);
        }
        registered.clear();
    }
}
