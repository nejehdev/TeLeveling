package dev.nejeh.televeling.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class DynamicCommandRegistrarTest {
    @Test
    void removesEveryAliasForTheUnregisteredCommandOnly() {
        Command removed = command("bedwarslevel");
        Command retained = command("otherlevel");
        Map<String, Command> knownCommands = new HashMap<>();
        knownCommands.put("bedwarslevel", removed);
        knownCommands.put("bwlevel", removed);
        knownCommands.put("otherlevel", retained);

        DynamicCommandRegistrar.removeCommandMappings(knownCommands, removed);

        assertFalse(knownCommands.containsKey("bedwarslevel"));
        assertFalse(knownCommands.containsKey("bwlevel"));
        assertSame(retained, knownCommands.get("otherlevel"));
    }

    private static Command command(String name) {
        return new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return true;
            }
        };
    }
}
