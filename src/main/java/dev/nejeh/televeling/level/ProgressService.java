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
import java.util.concurrent.CompletableFuture;
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
        List<LevelDefinition> definitions = List.copyOf(levels.all().values());
        database.loadAll(player.getUniqueId(), player.getName(), definitions).whenComplete((loaded, error) -> {
            if (error != null) {
                plugin.getLogger().severe("Could not load player data: " + error.getMessage());
                return;
            }
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        cache.putAll(loaded);
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
        return mutate(player, definition, mutation, true);
    }

    public CompletableFuture<PlayerLevelData> mutate(
            Player player,
            LevelDefinition definition,
            UnaryOperator<PlayerLevelData> mutation,
            boolean notifyLevelUp
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
            try {
                operation = CompletableFuture.completedFuture(mutation.apply(current));
            } catch (RuntimeException exception) {
                operation = CompletableFuture.failedFuture(exception);
            }
        }
        return operation.thenApply(updated -> {
            playerData.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                    .put(definition.id(), updated);
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        updateBar(player);
                        if (notifyLevelUp && updated.level() > previous.level()) {
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

    public CompletableFuture<Integer> resetAll(LevelDefinition definition) {
        List<Player> onlinePlayers = List.copyOf(Bukkit.getOnlinePlayers());
        return database.resetAll(definition).thenCompose(resetCount -> {
            List<CompletableFuture<PlayerLevelData>> onlineResets = onlinePlayers.stream()
                    .map(player -> database.update(
                            player.getUniqueId(),
                            player.getName(),
                            definition,
                            current -> current.withProgress(0, 0)
                    ))
                    .toList();
            return CompletableFuture.allOf(onlineResets.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> {
                        for (int index = 0; index < onlinePlayers.size(); index++) {
                            Player player = onlinePlayers.get(index);
                            Map<String, PlayerLevelData> cache = playerData.computeIfAbsent(
                                    player.getUniqueId(),
                                    unused -> new ConcurrentHashMap<>()
                            );
                            cache.put(definition.id(), onlineResets.get(index).join());
                        }
                        if (plugin.isEnabled()) {
                            Bukkit.getScheduler().runTask(plugin, () -> onlinePlayers.stream()
                                    .filter(Player::isOnline)
                                    .forEach(this::updateBar));
                        }
                        return resetCount;
                    });
        });
    }

    public void refreshOnlinePlayers() {
        List<LevelDefinition> definitions = List.copyOf(levels.all().values());
        String barLevelId = plugin.getConfig().getString("level-bar.name", "example");
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            String playerName = player.getName();
            Map<String, PlayerLevelData> cache = playerData.computeIfAbsent(
                    playerId,
                    ignored -> new ConcurrentHashMap<>()
            );
            database.loadAll(playerId, playerName, definitions).whenComplete((loaded, error) -> {
                if (error != null) {
                    plugin.getLogger().severe("Could not synchronize player data for "
                            + playerName + ": " + error.getMessage());
                    return;
                }
                boolean barChanged = false;
                for (Map.Entry<String, PlayerLevelData> entry : loaded.entrySet()) {
                    PlayerLevelData previous = cache.put(entry.getKey(), entry.getValue());
                    if (entry.getKey().equals(barLevelId) && !sameProgress(previous, entry.getValue())) {
                        barChanged = true;
                    }
                }
                if (barChanged && plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            updateBar(player);
                        }
                    });
                }
            });
        }
    }

    private static boolean sameProgress(PlayerLevelData first, PlayerLevelData second) {
        return first != null
                && first.level() == second.level()
                && Double.compare(first.experience(), second.experience()) == 0;
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
