package dev.nejeh.televeling.command;

import dev.nejeh.televeling.TeLevelingPlugin;
import dev.nejeh.televeling.level.LevelDefinition;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class DynamicLevelCommand extends Command {
    private final TeLevelingPlugin plugin;
    private final LevelDefinition definition;

    public DynamicLevelCommand(TeLevelingPlugin plugin, LevelDefinition definition) {
        super(definition.commandAliases().getFirst(),
                "Show your " + definition.name() + " level",
                "/" + definition.commandAliases().getFirst(),
                definition.commandAliases().stream().skip(1).toList());
        this.plugin = plugin;
        this.definition = definition;
        if (!definition.permission().isBlank()) {
            setPermission(definition.permission());
        }
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        return plugin.showLevel(sender, definition);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return List.of();
    }
}
