package dev.nejeh.televeling.text;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class MessageService {
    private final TextFormatter formatter;
    private org.bukkit.configuration.file.YamlConfiguration messages;

    public MessageService(TextFormatter formatter) {
        this.formatter = formatter;
    }

    public void setMessages(org.bukkit.configuration.file.YamlConfiguration messages) {
        this.messages = messages;
    }

    public Object get(String key) {
        return messages == null ? null : messages.get(key);
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> values) {
        Player player = sender instanceof Player target ? target : null;
        String prefix = messages == null ? "" : messages.getString("prefix", "");
        List<String> rendered = formatter.renderMessage(
                key,
                player,
                new TextFormatter.MapValues(values),
                prefix
        );
        for (String line : rendered) {
            sender.sendMessage(line);
        }
    }

    public void sendTitle(
            Player player,
            String titleKey,
            String subtitleKey,
            Map<String, String> values
    ) {
        TextFormatter.MapValues replacements = new TextFormatter.MapValues(values);
        List<String> titleLines = formatter.renderMessage(titleKey, player, replacements, "");
        List<String> subtitleLines = formatter.renderMessage(subtitleKey, player, replacements, "");
        player.sendTitle(
                titleLines.isEmpty() ? "" : titleLines.getFirst(),
                subtitleLines.isEmpty() ? "" : subtitleLines.getFirst(),
                10,
                60,
                10
        );
    }
}
