package dev.nejeh.televeling.level;

public final class LevelProgression {
    public static final int MAX_LEVEL_TRANSITIONS_PER_MUTATION = 10_000;

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
        int transitions = 0;
        double experience = data.experience() + amount;
        if (!Double.isFinite(experience)) {
            throw new IllegalArgumentException("Total experience must be finite.");
        }
        while (definition.hasNextLevel(level)) {
            double required = definition.requiredExperience(level);
            if (experience + 1.0E-9 < required) {
                break;
            }
            if (transitions == MAX_LEVEL_TRANSITIONS_PER_MUTATION) {
                throw new ProgressionLimitException();
            }
            experience = Math.max(0, experience - required);
            level++;
            transitions++;
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
        return data.withProgress(newLevel, data.experience());
    }

    public static PlayerLevelData addLevels(
            LevelDefinition definition,
            PlayerLevelData data,
            int amount
    ) {
        if (amount < 0) {
            throw new IllegalArgumentException("Level amount cannot be negative.");
        }
        long target = (long) data.level() + amount;
        if (definition.maxLevel() >= 0) {
            target = Math.min(target, definition.maxLevel());
        }
        if (target > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Target level is too large.");
        }
        return setLevel(data, (int) target);
    }

    public static PlayerLevelData removeLevels(
            PlayerLevelData data,
            int amount
    ) {
        if (amount < 0) {
            throw new IllegalArgumentException("Level amount cannot be negative.");
        }
        return setLevel(data, (int) Math.max(0L, (long) data.level() - amount));
    }

    public static PlayerLevelData removeExperience(
            LevelDefinition definition,
            PlayerLevelData data,
            double amount
    ) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Experience amount must be a non-negative finite number.");
        }
        int level = data.level();
        double experience = data.experience();
        double remaining = amount;
        int transitions = 0;

        while (remaining > experience && level > 0) {
            if (transitions == MAX_LEVEL_TRANSITIONS_PER_MUTATION) {
                throw new ProgressionLimitException();
            }
            remaining -= experience;
            level--;
            experience = definition.requiredExperience(level);
            transitions++;
        }
        experience = Math.max(0, experience - remaining);
        return data.withProgress(level, experience);
    }
}
