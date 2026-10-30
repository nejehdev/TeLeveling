package dev.nejeh.televeling.command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandNumberLimitsTest {
    @Test
    void acceptsPositiveDecimalExperienceWithinTheConfiguredBound() {
        assertEquals(1.0, CommandNumberLimits.parseExperience("1", 1_000_000));
        assertEquals(1.5, CommandNumberLimits.parseExperience("1.5", 1_000_000));
        assertEquals(1_000_000.0, CommandNumberLimits.parseExperience("1000000", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("101", 100));
        assertNull(CommandNumberLimits.parseExperience("1000001", 2_000_000));
    }

    @Test
    void rejectsZeroNegativeNonFiniteNonDecimalAndExcessiveExperience() {
        assertNull(CommandNumberLimits.parseExperience(".5", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("0", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("0.0", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("-1", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("+1", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("NaN", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("Infinity", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("1e2", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("0x1.0p0", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("1d", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("abc", 1_000_000));
        assertNull(CommandNumberLimits.parseExperience("1000001", 1_000_000));
    }

    @Test
    void acceptsOnlyPositiveIntegerLevelsWithinTheConfiguredBound() {
        assertEquals(1, CommandNumberLimits.parseLevel("1", 10_000));
        assertEquals(10_000, CommandNumberLimits.parseLevel("10000", 10_000));
        assertNull(CommandNumberLimits.parseLevel("0", 10_000));
        assertNull(CommandNumberLimits.parseLevel("-1", 10_000));
        assertNull(CommandNumberLimits.parseLevel("+1", 10_000));
        assertNull(CommandNumberLimits.parseLevel("10001", 10_000));
        assertNull(CommandNumberLimits.parseLevel("1.5", 10_000));
        assertNull(CommandNumberLimits.parseLevel("abc", 10_000));
        assertNull(CommandNumberLimits.parseLevel("51", 50));
        assertNull(CommandNumberLimits.parseLevel("10001", 20_000));
    }

    @Test
    void clampsConfiguredMaximumsToInternalCeilings() {
        List<String> warnings = new ArrayList<>();
        assertEquals(10_000, CommandNumberLimits.normalizeConfiguredMaximum(
                20_000, CommandNumberLimits.MAX_LEVEL_AMOUNT, "level-max", warnings::add));
        assertEquals(1_000_000, CommandNumberLimits.normalizeConfiguredMaximum(
                2_000_000, CommandNumberLimits.MAX_EXPERIENCE_AMOUNT, "xp-max", warnings::add));
        assertEquals(10_000, CommandNumberLimits.normalizeConfiguredMaximum(
                0, CommandNumberLimits.MAX_LEVEL_AMOUNT, "level-max", warnings::add));
        assertEquals(3, warnings.size());
        assertTrue(warnings.get(0).contains("level-max"));
        assertTrue(warnings.get(1).contains("xp-max"));
    }
}
