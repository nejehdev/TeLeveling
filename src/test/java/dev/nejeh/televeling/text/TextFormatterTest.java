package dev.nejeh.televeling.text;

import dev.nejeh.televeling.level.LevelDefinition;
import dev.nejeh.televeling.level.PlayerLevelData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextFormatterTest {
    @Test
    void appliesConfiguredFormatAndOmitsOnlyRequestedLevelComponents() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                name: Test
                command-alias: [testlevel]
                starting-level: 1
                starting-experience: 0
                symbol:
                  1: "◆"
                format: "%level%%symbol%"
                colors:
                  1: "&7"
                max-level: -1
                column-name: test_levels
                experience-require:
                  general-formula: "100"
                  level: {}
                """);
        LevelDefinition definition = LevelDefinition.from("test", yaml);
        PlayerLevelData data = new PlayerLevelData(UUID.randomUUID(), "Player", "test", 1, 0);
        TextFormatter formatter = new TextFormatter(null);

        assertEquals("§71◆", formatter.formatLevel(definition, data, true, true));
        assertEquals("§71", formatter.formatLevel(definition, data, true, false));
        assertEquals("1◆", formatter.formatLevel(definition, data, false, true));
    }
}
