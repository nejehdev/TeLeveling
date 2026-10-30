package dev.nejeh.televeling.level;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LevelProgressionTest {
    @Test
    void consumesExperienceAcrossLevelsUsingSpecificAndFormulaRequirements() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                name: BedWars
                command-alias: [bwlevel]
                starting-level: 0
                starting-experience: 0
                symbol:
                  0: "✦"
                  10: "✪"
                max-level: -1
                column-name: bedwars_levels
                syncronized: true
                experience-require:
                  general-formula: "100 * ({level} + 1)"
                  level:
                    0: 50
                """);
        LevelDefinition definition = LevelDefinition.from("bedwars", yaml);
        assertEquals("✪", definition.symbolAt(10));
        PlayerLevelData initial = new PlayerLevelData(UUID.randomUUID(), "Player", "bedwars", 0, 0);

        PlayerLevelData afterFortyNine = LevelProgression.addExperience(definition, initial, 49);
        assertEquals(0, afterFortyNine.level());
        assertEquals(49, afterFortyNine.experience());

        PlayerLevelData afterTwoHundredFifty = LevelProgression.addExperience(definition, initial, 250);
        assertEquals(2, afterTwoHundredFifty.level());
        assertEquals(0, afterTwoHundredFifty.experience());
    }

    @Test
    void stopsAtConfiguredMaximumLevel() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                name: Test
                command-alias: [testlevel]
                starting-level: 1
                starting-experience: 0
                max-level: 1
                column-name: test_levels
                experience-require:
                  general-formula: "10"
                  level: {}
                """);
        LevelDefinition definition = LevelDefinition.from("test", yaml);
        PlayerLevelData initial = new PlayerLevelData(UUID.randomUUID(), "Player", "test", 1, 0);

        PlayerLevelData result = LevelProgression.addExperience(definition, initial, 1000);
        assertEquals(1, result.level());
        assertEquals(0, result.experience());
    }
}
