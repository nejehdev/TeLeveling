package dev.nejeh.televeling.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nejeh.televeling.level.LevelDefinition;
import dev.nejeh.televeling.level.PlayerLevelData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

public final class DatabaseManager implements AutoCloseable {
    private static final String TABLE = "televeling_player_levels";

    private final JavaPlugin plugin;
    private final HikariDataSource dataSource;
    private final boolean mysql;
    private final ExecutorService databaseExecutor;

    public DatabaseManager(JavaPlugin plugin, FileConfiguration config) throws SQLException {
        this.plugin = plugin;
        String storageType = config.getString("storage.type", "SQLITE");
        if (!"MYSQL".equalsIgnoreCase(storageType) && !"SQLITE".equalsIgnoreCase(storageType)) {
            throw new SQLException("storage.type must be SQLITE or MYSQL.");
        }
        this.mysql = "MYSQL".equalsIgnoreCase(storageType);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(512),
                task -> {
                    Thread thread = new Thread(task, "TeLeveling-Database");
                    thread.setDaemon(true);
                    return thread;
                }
        );
        executor.allowCoreThreadTimeOut(true);
        this.databaseExecutor = executor;

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("TeLeveling");
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(0);
        hikari.setIdleTimeout(30000);
        hikari.setConnectionTimeout(config.getLong("storage.mysql.connection-timeout", 10000));

        if (mysql) {
            String host = config.getString("storage.mysql.host", "localhost");
            int port = config.getInt("storage.mysql.port", 3306);
            String database = config.getString("storage.mysql.database", "televeling");
            boolean useSsl = config.getBoolean("storage.mysql.use-ssl", false);
            boolean verifyCertificate = config.getBoolean("storage.mysql.verify-server-certificate", true);
            boolean allowPublicKey = config.getBoolean("storage.mysql.allow-public-key-retrieval", false);
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSsl
                    + "&verifyServerCertificate=" + verifyCertificate
                    + "&allowPublicKeyRetrieval=" + allowPublicKey
                    + "&serverTimezone=UTC");
            hikari.setUsername(config.getString("storage.mysql.username", "root"));
            hikari.setPassword(config.getString("storage.mysql.password", ""));
        } else {
            File databaseFile = new File(plugin.getDataFolder(),
                    config.getString("storage.sqlite.file", "data.db"));
            File parent = databaseFile.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new SQLException("Could not create SQLite database directory: " + parent);
            }
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            hikari.setConnectionInitSql("PRAGMA busy_timeout=5000");
        }

        this.dataSource = new HikariDataSource(hikari);
        try {
            createSchema();
        } catch (SQLException exception) {
            dataSource.close();
            databaseExecutor.shutdownNow();
            throw exception;
        }
    }

    public CompletableFuture<Map<String, PlayerLevelData>> loadAll(
            UUID playerId,
            String playerName,
            Collection<LevelDefinition> definitions
    ) {
        Map<String, LevelDefinition> synchronizedColumns = new HashMap<>();
        for (LevelDefinition definition : definitions) {
            if (definition.synchronizedData()) {
                synchronizedColumns.put(definition.columnName().toLowerCase(Locale.ROOT), definition);
            }
        }
        if (synchronizedColumns.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        return submit(() -> {
            Map<String, PlayerLevelData> result = new HashMap<>();
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT column_name, player_level, experience FROM " + TABLE
                                 + " WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        String columnName = results.getString("column_name").toLowerCase(Locale.ROOT);
                        LevelDefinition definition = synchronizedColumns.get(columnName);
                        if (definition != null) {
                            result.put(definition.id(), new PlayerLevelData(
                                    playerId,
                                    playerName,
                                    definition.id(),
                                    results.getInt("player_level"),
                                    results.getDouble("experience")
                            ));
                        }
                    }
                }
                return Map.copyOf(result);
            }
        });
    }

    public CompletableFuture<PlayerLevelData> update(
            UUID playerId,
            String playerName,
            LevelDefinition definition,
            UnaryOperator<PlayerLevelData> mutation
    ) {
        PlayerLevelData defaults = new PlayerLevelData(
                playerId,
                playerName,
                definition.id(),
                definition.startingLevel(),
                definition.startingExperience()
        );
        if (!definition.synchronizedData()) {
            return CompletableFuture.completedFuture(mutation.apply(defaults));
        }
        return submit(() -> updateBlocking(defaults, definition, mutation));
    }

    private PlayerLevelData updateBlocking(
            PlayerLevelData defaults,
            LevelDefinition definition,
            UnaryOperator<PlayerLevelData> mutation
    ) throws SQLException {
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureRow(connection, defaults, definition);
                PlayerLevelData current = selectForUpdate(connection, defaults, definition);
                PlayerLevelData changed = mutation.apply(current);
                PlayerLevelData updated = new PlayerLevelData(
                        defaults.playerId(),
                        defaults.playerName(),
                        defaults.levelId(),
                        changed.level(),
                        changed.experience()
                );
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + TABLE
                                + " SET player_name = ?, level_id = ?, column_name = ?, player_level = ?, experience = ?, updated_at = ?"
                                + " WHERE player_uuid = ? AND column_name = ?")) {
                    statement.setString(1, updated.playerName());
                    statement.setString(2, definition.id());
                    statement.setString(3, definition.columnName());
                    statement.setInt(4, updated.level());
                    statement.setDouble(5, updated.experience());
                    statement.setLong(6, System.currentTimeMillis());
                    statement.setString(7, updated.playerId().toString());
                    statement.setString(8, definition.columnName());
                    statement.executeUpdate();
                }
                connection.commit();
                return updated;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void ensureRow(Connection connection, PlayerLevelData defaults, LevelDefinition definition)
            throws SQLException {
        String sql = mysql
                ? "INSERT INTO " + TABLE
                    + " (player_uuid, player_name, level_id, column_name, player_level, experience, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_name = VALUES(player_name)"
                : "INSERT OR IGNORE INTO " + TABLE
                    + " (player_uuid, player_name, level_id, column_name, player_level, experience, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, defaults.playerId().toString());
            statement.setString(2, defaults.playerName());
            statement.setString(3, definition.id());
            statement.setString(4, definition.columnName());
            statement.setInt(5, defaults.level());
            statement.setDouble(6, defaults.experience());
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private PlayerLevelData selectForUpdate(
            Connection connection,
            PlayerLevelData key,
            LevelDefinition definition
    ) throws SQLException {
        String sql = "SELECT player_name, player_level, experience FROM " + TABLE
                + " WHERE player_uuid = ? AND column_name = ?" + (mysql ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.playerId().toString());
            statement.setString(2, definition.columnName());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return key;
                }
                return new PlayerLevelData(
                        key.playerId(),
                        results.getString("player_name"),
                        key.levelId(),
                        results.getInt("player_level"),
                        results.getDouble("experience")
                );
            }
        }
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private void createSchema() throws SQLException {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            if (mysql) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "player_uuid VARCHAR(36) NOT NULL,"
                        + "player_name VARCHAR(16) NOT NULL,"
                        + "level_id VARCHAR(64) NOT NULL,"
                        + "column_name VARCHAR(64) NOT NULL,"
                        + "player_level INT NOT NULL,"
                        + "experience DOUBLE NOT NULL,"
                        + "updated_at BIGINT NOT NULL,"
                        + "PRIMARY KEY (player_uuid, column_name)"
                        + ")");
            } else {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "player_uuid VARCHAR(36) NOT NULL,"
                        + "player_name VARCHAR(16) NOT NULL,"
                        + "level_id VARCHAR(64) NOT NULL,"
                        + "column_name VARCHAR(64) NOT NULL,"
                        + "player_level INTEGER NOT NULL,"
                        + "experience REAL NOT NULL,"
                        + "updated_at INTEGER NOT NULL,"
                        + "PRIMARY KEY (player_uuid, column_name)"
                        + ")");
            }
        }
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.run();
                } catch (SQLException exception) {
                    throw new DatabaseOperationException(exception);
                }
            }, databaseExecutor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(
                    new DatabaseOperationException("Database task queue is full.", exception)
            );
        }
    }

    @Override
    public void close() {
        databaseExecutor.shutdown();
        try {
            if (!databaseExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                databaseExecutor.shutdownNow();
                plugin.getLogger().warning("Database operations did not finish before shutdown.");
            }
        } catch (InterruptedException exception) {
            databaseExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run() throws SQLException;
    }

    public static final class DatabaseOperationException extends RuntimeException {
        public DatabaseOperationException(SQLException cause) {
            super(cause);
        }

        public DatabaseOperationException(String message, RejectedExecutionException cause) {
            super(message, cause);
        }
    }
}
