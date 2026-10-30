package dev.nejeh.televeling.level;

import dev.nejeh.televeling.TeLevelingPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LevelRegistry {
    private final TeLevelingPlugin plugin;
    private Map<String, LevelDefinition> levels = Map.of();
    private Map<String, LevelDefinition> aliases = Map.of();

    public LevelRegistry(TeLevelingPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() throws IOException {
        File directory = new File(plugin.getDataFolder(), "levels");
        boolean firstCreation = !directory.exists();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create levels directory: " + directory);
        }

        if (firstCreation) {
            plugin.saveResource("levels/example.yml", false);
        } else if (directory.listFiles() == null) {
            throw new IOException("Could not read levels directory: " + directory);
        }

        File[] files = directory.listFiles((parent, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            throw new IOException("Could not read levels directory: " + directory);
        }
        List<File> sortedFiles = new ArrayList<>(List.of(files));
        sortedFiles.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        InputStream resource = plugin.getResource("levels/example.yml");
        if (resource == null) {
            throw new IOException("Missing default level resource levels/example.yml");
        }
        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            defaults = new YamlConfiguration();
            defaults.load(reader);
        } catch (org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IOException("Invalid default level resource.", exception);
        }
        Map<String, LevelDefinition> loaded = new LinkedHashMap<>();
        Map<String, LevelDefinition> loadedAliases = new LinkedHashMap<>();
        Map<String, String> columns = new LinkedHashMap<>();

        for (File file : sortedFiles) {
            String id = file.getName().substring(0, file.getName().length() - 4)
                    .toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_-]{1,64}")) {
                plugin.getLogger().warning("Ignoring level file with invalid name: " + file.getName());
                continue;
            }

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            boolean hasName = yaml.contains("name");
            boolean hasAliases = yaml.contains("command-alias");
            boolean hasColumnName = yaml.contains("column-name");
            yaml.addDefaults(defaults);
            yaml.options().copyDefaults(true);
            if (!hasName) {
                yaml.set("name", id);
            }
            if (!hasAliases) {
                yaml.set("command-alias", List.of(id + "level"));
            }
            if (!hasColumnName) {
                yaml.set("column-name", id);
            }
            yaml.save(file);

            try {
                LevelDefinition definition = LevelDefinition.from(id, yaml);
                String previousColumn = columns.putIfAbsent(
                        definition.columnName().toLowerCase(Locale.ROOT),
                        definition.id()
                );
                if (previousColumn != null) {
                    throw new IllegalArgumentException(
                            "column-name is already used by level '" + previousColumn + "'."
                    );
                }
                loaded.put(id, definition);
                for (String alias : definition.commandAliases()) {
                    if (!alias.matches("[a-z0-9_-]{1,64}")) {
                        plugin.getLogger().warning("Ignoring invalid command alias '" + alias + "' in " + file.getName());
                        continue;
                    }
                    LevelDefinition previous = loadedAliases.putIfAbsent(alias, definition);
                    if (previous != null) {
                        loadedAliases.put(alias, previous);
                        plugin.getLogger().warning(
                                "Ignoring duplicate command alias '" + alias + "' in " + file.getName()
                        );
                    }
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Could not load level file " + file.getName() + ": " + exception.getMessage());
            }
        }
        this.levels = Map.copyOf(loaded);
        this.aliases = Map.copyOf(loadedAliases);
    }

    public Map<String, LevelDefinition> all() {
        return levels;
    }

    public LevelDefinition get(String id) {
        return levels.get(id.toLowerCase(Locale.ROOT));
    }

    public LevelDefinition byAlias(String alias) {
        return aliases.get(alias.toLowerCase(Locale.ROOT));
    }
}
