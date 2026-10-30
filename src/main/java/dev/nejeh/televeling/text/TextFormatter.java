package dev.nejeh.televeling.text;

import dev.nejeh.televeling.TeLevelingPlugin;
import dev.nejeh.televeling.level.LevelDefinition;
import dev.nejeh.televeling.level.PlayerLevelData;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class TextFormatter {
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final TeLevelingPlugin plugin;

    public TextFormatter(TeLevelingPlugin plugin) {
        this.plugin = plugin;
    }

    public String formatLevel(LevelDefinition definition, PlayerLevelData data, boolean includeColor, boolean includeSymbol) {
        String symbol = includeSymbol ? definition.symbolAt(data.level()) : "";
        String text = definition.format()
                .replace("%level%", Integer.toString(data.level()))
                .replace("%symbol%", symbol);
        if (includeColor) {
            text = applyColorSequence(text, definition.colorAt(data.level()));
        } else {
            text = PLAIN.serialize(parseLegacy(text));
        }
        return SECTION.serialize(parseLegacy(text));
    }

    public List<String> renderMessage(String key, Player player, MapValues values, String prefix) {
        Object raw = plugin.messages().get(key);
        if (raw == null) {
            return List.of();
        }
        List<String> lines = raw instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of(String.valueOf(raw));

        List<String> rendered = new ArrayList<>(lines.size());
        for (String line : lines) {
            String linePrefix = line.contains("{line}") ? "" : prefix;
            String replaced = values.apply(linePrefix + line);
            if (player != null && plugin.isPlaceholderApiAvailable()) {
                replaced = PlaceholderAPI.setPlaceholders(player, replaced);
            }
            replaced = applyLocalPlaceholders(replaced);
            rendered.add(SECTION.serialize(parseLegacy(replaced)));
        }
        return rendered;
    }

    public String applyLocalPlaceholders(String input) {
        int width = Math.clamp(plugin.getConfig().getInt("messages.center-width", 53), 1, 200);
        String withLine = input.replace("{line}", plugin.lineIcon().repeat(width));
        if (!withLine.contains("{center}")) {
            return withLine;
        }
        String text = withLine.replace("{center}", "");
        int padding = Math.max(0, (width - PLAIN.serialize(parseLegacy(text)).length()) / 2);
        return " ".repeat(padding) + text;
    }

    private static net.kyori.adventure.text.Component parseLegacy(String input) {
        return AMPERSAND.deserialize(input.replace('\u00a7', '&'));
    }

    private String applyColorSequence(String text, String configuredColors) {
        if (configuredColors == null || configuredColors.isBlank()) {
            return text;
        }
        List<String> colors = List.of(configuredColors.split("\\s*,\\s*"));
        if (colors.size() == 1) {
            return colors.getFirst() + text;
        }
        StringBuilder result = new StringBuilder();
        int colorIndex = 0;
        for (int index = 0; index < text.length();) {
            char character = text.charAt(index);
            if (character == '&' && index + 1 < text.length()) {
                result.append(character).append(text.charAt(++index));
                index++;
                continue;
            }
            int codePoint = text.codePointAt(index);
            result.append(colors.get(colorIndex++ % colors.size())).appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }
        return result.toString();
    }

    public record MapValues(java.util.Map<String, String> values) {
        public String apply(String input) {
            String result = input;
            for (var entry : values.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            return result;
        }

        public static MapValues of(String key, String value) {
            return new MapValues(java.util.Map.of(key, value));
        }
    }
}
