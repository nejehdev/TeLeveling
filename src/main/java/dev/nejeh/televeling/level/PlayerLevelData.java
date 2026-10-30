package dev.nejeh.televeling.level;

import java.util.UUID;

public record PlayerLevelData(UUID playerId, String playerName, String levelId, int level, double experience) {
    public PlayerLevelData {
        if (level < 0 || !Double.isFinite(experience) || experience < 0) {
            throw new IllegalArgumentException("Player level and experience must be non-negative.");
        }
    }

    public PlayerLevelData withProgress(int newLevel, double newExperience) {
        return new PlayerLevelData(playerId, playerName, levelId, newLevel, newExperience);
    }
}
