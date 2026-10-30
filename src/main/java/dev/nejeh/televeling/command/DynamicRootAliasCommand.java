package dev.nejeh.televeling.command;

import dev.nejeh.televeling.TeLevelingPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class DynamicRootAliasCommand extends Command {
    private final TeLevelingPlugin plugin;

    public DynamicRootAliasCommand(TeLevelingPlugin plugin, String alias) {
        super(alias, "TeLeveling management command", "/" + alias, List.of());
        this.plugin = plugin;
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        return plugin.executeRootCommand(sender, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return plugin.completeRootCommand(args);
    }
}
