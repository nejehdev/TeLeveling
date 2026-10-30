package dev.nejeh.televeling.level;

import dev.nejeh.televeling.TeLevelingPlugin;
import dev.nejeh.televeling.storage.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class ProgressService {
    private final TeLevelingPlugin plugin;
    private final DatabaseManager database;
    private final LevelRegistry levels;
    private final Map<UUID, Map<String, PlayerLevelData>> playerData = new ConcurrentHashMap<>();

    public ProgressService(TeLevelingPlugin plugin, DatabaseManager database, LevelRegistry levels) {
        this.plugin = plugin;
        this.database = database;
        this.levels = levels;
    }

    public void loadPlayer(Player player) {
        Map<String, PlayerLevelData> cache = playerData.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new ConcurrentHashMap<>()
        );
        List<CompletableFuture<PlayerLevelData>> loads = levels.all().values().stream()
                .map(definition -> database.load(player.getUniqueId(), player.getName(), definition))
                .toList();
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().severe("Could not load player data: " + error.getMessage());
                return;
            }
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        int index = 0;
                        for (LevelDefinition definition : levels.all().values()) {
                            cache.put(definition.id(), loads.get(index++).join());
                        }
                        updateBar(player);
                    }
                });
            }
        });
    }

    public void unloadPlayer(Player player) {
        playerData.remove(player.getUniqueId());
    }

    public PlayerLevelData get(Player player, LevelDefinition definition) {
        return get(player.getUniqueId(), player.getName(), definition);
    }

    public PlayerLevelData get(UUID playerId, String playerName, LevelDefinition definition) {
        Map<String, PlayerLevelData> playerLevels = playerData.get(playerId);
        if (playerLevels != null) {
            PlayerLevelData data = playerLevels.get(definition.id());
            if (data != null) {
                return data;
            }
        }
        return new PlayerLevelData(
                playerId,
                playerName == null ? "" : playerName,
                definition.id(),
                definition.startingLevel(),
                definition.startingExperience()
        );
    }

    public CompletableFuture<PlayerLevelData> mutate(
            Player player,
            LevelDefinition definition,
            UnaryOperator<PlayerLevelData> mutation
    ) {
        PlayerLevelData previous = get(player, definition);
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        CompletableFuture<PlayerLevelData> operation;
        if (definition.synchronizedData()) {
            operation = database.update(
                    playerId,
                    playerName,
                    definition,
                    mutation
            );
        } else {
            PlayerLevelData current = get(player, definition);
            operation = CompletableFuture.completedFuture(mutation.apply(current));
        }
        return operation.thenApply(updated -> {
            playerData.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                    .put(definition.id(), updated);
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        updateBar(player);
                        if (updated.level() > previous.level()) {
                            plugin.messages().send(player, "level-up", Map.of(
                                    "level", plugin.text().formatLevel(definition, updated, true, true),
                                    "level_name", definition.name(),
                                    "xp", formatNumber(updated.experience()),
                                    "xp_required", formatNumber(requiredExperience(definition, updated))
                            ));
                        }
                    }
                });
            }
            return updated;
        });
    }

    public void refreshOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String playerName = player.getName();
            Map<String, PlayerLevelData> cache = playerData.computeIfAbsent(
                    player.getUniqueId(),
                    ignored -> new ConcurrentHashMap<>()
            );
            for (LevelDefinition definition : levels.all().values()) {
                if (!definition.synchronizedData()) {
                    continue;
                }
                database.load(player.getUniqueId(), player.getName(), definition)
                        .thenAccept(data -> {
                            cache.put(definition.id(), data);
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (player.isOnline()) {
                                    updateBar(player);
                                }
                            });
                        })
                        .exceptionally(error -> {
                            plugin.getLogger().severe("Could not synchronize player data for "
                                    + playerName + ": " + error.getMessage());
                            return null;
                        });
            }
        }
    }

    public void updateBar(Player player) {
        if (!plugin.getConfig().getBoolean("level-bar.enabled", true)) {
            return;
        }
        LevelDefinition definition = levels.get(plugin.getConfig().getString("level-bar.name", "example"));
        if (definition == null) {
            return;
        }
        PlayerLevelData data = get(player, definition);
        float progress = 1.0F;
        if (definition.hasNextLevel(data.level())) {
            double required = requiredExperience(definition, data);
            progress = (float) Math.clamp(data.experience() / required, 0.0, 1.0);
        }
        player.sendExperienceChange(progress, player.getLevel());
    }

    public double requiredExperience(LevelDefinition definition, PlayerLevelData data) {
        return definition.hasNextLevel(data.level()) ? definition.requiredExperience(data.level()) : 0;
    }

    public double round(double value) {
        int digits = Math.clamp(plugin.getConfig().getInt("round-digits", 2), 0, 15);
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }

    public String formatNumber(double value) {
        return BigDecimal.valueOf(round(value)).stripTrailingZeros().toPlainString();
    }
}
