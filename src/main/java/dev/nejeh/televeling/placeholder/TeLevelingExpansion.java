package dev.nejeh.televeling.placeholder;

import dev.nejeh.televeling.TeLevelingPlugin;
import dev.nejeh.televeling.level.LevelDefinition;
import dev.nejeh.televeling.level.PlayerLevelData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class TeLevelingExpansion extends PlaceholderExpansion {
    private final TeLevelingPlugin plugin;

    public TeLevelingExpansion(TeLevelingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "televeling";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Nejeh";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return null;
        }
        String normalized = params.toLowerCase(Locale.ROOT);
        boolean next = normalized.startsWith("nextlevel_");
        String selector = next ? normalized.substring("nextlevel_".length()) : normalized;
        if (selector.startsWith("level_")) {
            return levelValue(player, selector.substring("level_".length()), next);
        }
        if (selector.startsWith("xp_required_")) {
            return experienceRequired(player, selector.substring("xp_required_".length()), next);
        }
        if (selector.startsWith("xp_")) {
            return experience(player, selector.substring("xp_".length()));
        }
        return null;
    }

    private String levelValue(OfflinePlayer player, String selector, boolean next) {
        boolean noColor = selector.startsWith("nocolor_");
        boolean noIcon = selector.startsWith("noicon_");
        boolean raw = selector.startsWith("raw_");
        String levelId = noColor ? selector.substring(8)
                : noIcon ? selector.substring(7)
                : raw ? selector.substring(4) : selector;
        LevelDefinition definition = plugin.levels().get(levelId);
        if (definition == null) {
            return null;
        }
        PlayerLevelData data = plugin.progress().get(player.getUniqueId(), player.getName(), definition);
        if (next && definition.hasNextLevel(data.level())) {
            data = data.withProgress(data.level() + 1, 0);
        }
        if (raw) {
            return Integer.toString(data.level());
        }
        return plugin.text().formatLevel(definition, data, !noColor, !noIcon);
    }

    private String experience(OfflinePlayer player, String levelId) {
        LevelDefinition definition = plugin.levels().get(levelId);
        if (definition == null) {
            return null;
        }
        return plugin.progress().formatNumber(
                plugin.progress().get(player.getUniqueId(), player.getName(), definition).experience()
        );
    }

    private String experienceRequired(OfflinePlayer player, String levelId, boolean next) {
        LevelDefinition definition = plugin.levels().get(levelId);
        if (definition == null) {
            return null;
        }
        PlayerLevelData data = plugin.progress().get(player.getUniqueId(), player.getName(), definition);
        if (next && definition.hasNextLevel(data.level())) {
            data = data.withProgress(data.level() + 1, 0);
        }
        return plugin.progress().formatNumber(plugin.progress().requiredExperience(definition, data));
    }
}
