package dev.nejeh.televeling.level;

public final class LevelProgression {
    private LevelProgression() {
    }

    public static PlayerLevelData addExperience(
            LevelDefinition definition,
            PlayerLevelData data,
            double amount
    ) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Experience amount must be a non-negative finite number.");
        }
        if (!definition.hasNextLevel(data.level())) {
            return data;
        }

        int level = data.level();
        double experience = data.experience() + amount;
        if (!Double.isFinite(experience)) {
            throw new IllegalArgumentException("Total experience must be finite.");
        }
        while (definition.hasNextLevel(level)) {
            double required = definition.requiredExperience(level);
            if (experience + 1.0E-9 < required) {
                break;
            }
            experience = Math.max(0, experience - required);
            level++;
        }
        return data.withProgress(level, experience);
    }

    public static PlayerLevelData setExperience(
            LevelDefinition definition,
            PlayerLevelData data,
            double amount
    ) {
        return addExperience(definition, data.withProgress(data.level(), 0), amount);
    }

    public static PlayerLevelData setLevel(PlayerLevelData data, int newLevel) {
        if (newLevel < 0) {
            throw new IllegalArgumentException("Level cannot be negative.");
        }
        return data.withProgress(newLevel, 0);
    }
}
