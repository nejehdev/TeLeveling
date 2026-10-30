package dev.nejeh.televeling.level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.Locale;

public final class LevelDefinition {
    private final String id;
    private final String name;
    private final List<String> commandAliases;
    private final int startingLevel;
    private final double startingExperience;
    private final NavigableMap<Integer, String> symbols;
    private final String format;
    private final NavigableMap<Integer, String> colors;
    private final int maxLevel;
    private final String permission;
    private final String columnName;
    private final boolean synchronizedData;
    private final String generalFormula;
    private final NavigableMap<Integer, Double> experienceRequirements;

    private LevelDefinition(
            String id,
            String name,
            List<String> commandAliases,
            int startingLevel,
            double startingExperience,
            NavigableMap<Integer, String> symbols,
            String format,
            NavigableMap<Integer, String> colors,
            int maxLevel,
            String permission,
            String columnName,
            boolean synchronizedData,
            String generalFormula,
            NavigableMap<Integer, Double> experienceRequirements
    ) {
        this.id = id;
        this.name = name;
        this.commandAliases = List.copyOf(commandAliases);
        this.startingLevel = startingLevel;
        this.startingExperience = startingExperience;
        this.symbols = symbols;
        this.format = format;
        this.colors = colors;
        this.maxLevel = maxLevel;
        this.permission = permission;
        this.columnName = columnName;
        this.synchronizedData = synchronizedData;
        this.generalFormula = generalFormula;
        this.experienceRequirements = experienceRequirements;
    }

    public static LevelDefinition from(String id, YamlConfiguration yaml) {
        String name = yaml.getString("name", id);
        List<String> aliases = new ArrayList<>();
        for (String alias : yaml.getStringList("command-alias")) {
            String normalized = alias.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                aliases.add(normalized);
            }
        }

        int startingLevel = yaml.getInt("starting-level", 0);
        double startingExperience = yaml.getDouble("starting-experience", 0);
        int maxLevel = yaml.getInt("max-level", -1);
        if (startingLevel < 0 || startingExperience < 0 || !Double.isFinite(startingExperience)) {
            throw new IllegalArgumentException("Starting level and experience must be non-negative.");
        }
        if (maxLevel < -1 || (maxLevel >= 0 && startingLevel > maxLevel)) {
            throw new IllegalArgumentException("Starting level cannot exceed max-level.");
        }

        NavigableMap<Integer, String> symbols = readStringMap(yaml, "symbols", "symbol");
        NavigableMap<Integer, String> colors = readStringMap(yaml, "colors", null);
        NavigableMap<Integer, Double> requirements = new TreeMap<>();
        ConfigurationSection requiredSection = yaml.getConfigurationSection("experience-require.level");
        if (requiredSection != null) {
            for (String key : requiredSection.getKeys(false)) {
                int level = Integer.parseInt(key);
                double amount = requiredSection.getDouble(key);
                if (level < 0 || amount <= 0 || !Double.isFinite(amount)) {
                    throw new IllegalArgumentException("Experience requirements must be positive finite values.");
                }
                requirements.put(level, amount);
            }
        }

        String formula = yaml.getString("experience-require.general-formula", "100 * ({level} + 1)");
        if (formula == null || formula.isBlank()) {
            throw new IllegalArgumentException("experience-require.general-formula cannot be empty.");
        }
        double formulaResult = FormulaEvaluator.evaluate(formula, startingLevel);
        if (!requirements.containsKey(startingLevel)
                && (maxLevel < 0 || startingLevel < maxLevel)
                && formulaResult <= 0) {
            throw new IllegalArgumentException(
                    "The formula must produce positive experience for starting level " + startingLevel + "."
            );
        }

        String columnName = yaml.getString("column-name", id).trim();
        if (!columnName.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("column-name must contain 1-64 letters, numbers, underscores, or hyphens.");
        }

        return new LevelDefinition(
                id,
                name,
                aliases,
                startingLevel,
                startingExperience,
                symbols,
                yaml.getString("format", "%level% %symbol%"),
                colors,
                maxLevel,
                yaml.getString("permission", ""),
                columnName,
                yaml.getBoolean("syncronized", true),
                formula,
                requirements
        );
    }

    private static NavigableMap<Integer, String> readStringMap(
            YamlConfiguration yaml,
            String primaryPath,
            String fallbackPath
    ) {
        ConfigurationSection section = yaml.getConfigurationSection(primaryPath);
        if (section == null && fallbackPath != null) {
            section = yaml.getConfigurationSection(fallbackPath);
        }
        NavigableMap<Integer, String> result = new TreeMap<>();
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            int level = Integer.parseInt(key);
            if (level < 0) {
                throw new IllegalArgumentException(primaryPath + " keys cannot be negative.");
            }
            result.put(level, section.getString(key, ""));
        }
        return result;
    }

    public double requiredExperience(int level) {
        Double explicit = experienceRequirements.get(level);
        double required = explicit != null ? explicit : FormulaEvaluator.evaluate(generalFormula, level);
        if (!Double.isFinite(required) || required <= 0) {
            throw new IllegalArgumentException("Experience required for level " + level + " must be positive.");
        }
        return required;
    }

    public String symbolAt(int level) {
        Map.Entry<Integer, String> entry = symbols.floorEntry(level);
        return entry == null ? "" : entry.getValue();
    }

    public String colorAt(int level) {
        Map.Entry<Integer, String> entry = colors.floorEntry(level);
        return entry == null ? "" : entry.getValue();
    }

    public boolean hasNextLevel(int currentLevel) {
        return maxLevel < 0 || currentLevel < maxLevel;
    }

    public String format() {
        return format;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<String> commandAliases() {
        return commandAliases;
    }

    public int startingLevel() {
        return startingLevel;
    }

    public double startingExperience() {
        return startingExperience;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public String permission() {
        return permission;
    }

    public String columnName() {
        return columnName;
    }

    public boolean synchronizedData() {
        return synchronizedData;
    }
}
