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
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        return plugin.executeLevelCommand(sender, definition, args, commandLabel);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return plugin.completeLevelCommand(sender, definition, args);
    }
}
