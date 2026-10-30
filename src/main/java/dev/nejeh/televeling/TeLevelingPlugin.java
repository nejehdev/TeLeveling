package dev.nejeh.televeling;

import dev.nejeh.televeling.command.DynamicCommandRegistrar;
import dev.nejeh.televeling.level.LevelDefinition;
import dev.nejeh.televeling.level.LevelProgression;
import dev.nejeh.televeling.level.LevelRegistry;
import dev.nejeh.televeling.level.PlayerLevelData;
import dev.nejeh.televeling.level.ProgressService;
import dev.nejeh.televeling.placeholder.TeLevelingExpansion;
import dev.nejeh.televeling.storage.DatabaseManager;
import dev.nejeh.televeling.text.MessageService;
import dev.nejeh.televeling.text.TextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class TeLevelingPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private LevelRegistry levels;
    private DatabaseManager database;
    private ProgressService progress;
    private TextFormatter text;
    private MessageService messages;
    private DynamicCommandRegistrar dynamicCommands;
    private TeLevelingExpansion placeholderExpansion;
    private boolean placeholderApiAvailable;
    private String lineIcon;

    @Override
    public void onEnable() {
        getLogger().info("Detected Java runtime: " + Runtime.version());
        if (Runtime.version().feature() < 21) {
            getLogger().warning("TeLeveling targets Java 21+. This server is running Java "
                    + Runtime.version().feature() + "; use a Java version supported by your Minecraft server.");
        }

        try {
            prepareConfigurationFiles();
            levels = new LevelRegistry(this);
            levels.reload();
            text = new TextFormatter(this);
            messages = new MessageService(text);
            loadMessages();
            lineIcon = getConfig().getString("line-icon", "&8■");

            database = new DatabaseManager(this, getConfig());
            progress = new ProgressService(this, database, levels);
            dynamicCommands = new DynamicCommandRegistrar(this);
            dynamicCommands.reload();
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "TeLeveling could not start.", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (getCommand("televeling") == null) {
            getLogger().severe("The televeling command is missing from plugin.yml.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("televeling").setExecutor(this);
        getCommand("televeling").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        setupPlaceholderApi();

        long syncInterval = Math.max(1, getConfig().getLong("save-time", 10));
        Bukkit.getScheduler().runTaskTimer(
                this,
                progress::refreshOnlinePlayers,
                syncInterval * 20,
                syncInterval * 20
        );
        for (Player player : Bukkit.getOnlinePlayers()) {
            progress.loadPlayer(player);
        }
        getLogger().info("TeLeveling v" + getPluginMeta().getVersion() + " enabled with "
                + levels.all().size() + " configured level(s).");
    }

    @Override
    public void onDisable() {
        if (dynamicCommands != null) {
            dynamicCommands.unregisterAll();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        if (database != null) {
            database.close();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        progress.loadPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        progress.unloadPlayer(event.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            messages.send(sender, "help");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("televeling.admin")) {
                messages.send(sender, "no-permission");
                return true;
            }
            reloadPluginFiles(sender);
            return true;
        }
        if (!sender.hasPermission("televeling.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length < 3) {
            messages.send(sender, "invalid-command", Map.of(
                    "usage", "/televeling " + args[0] + " <jugador> <nivel> [cantidad]"
            ));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "player-not-found");
            return true;
        }
        LevelDefinition definition = levels.get(args[2]);
        if (definition == null) {
            messages.send(sender, "level-not-found", Map.of("level", args[2]));
            return true;
        }

        if (args[0].equalsIgnoreCase("reset")) {
            progress.mutate(target, definition, ignored -> new PlayerLevelData(
                    target.getUniqueId(),
                    target.getName(),
                    definition.id(),
                    definition.startingLevel(),
                    definition.startingExperience()
            )).whenComplete((data, error) -> completeMutation(
                    sender,
                    error,
                    "progress-reset",
                    target,
                    definition,
                    0
            ));
            return true;
        }

        if (args.length < 4) {
            messages.send(sender, "invalid-command", Map.of(
                    "usage", "/televeling " + args[0] + " <jugador> <nivel> <cantidad>"
            ));
            return true;
        }

        if (args[0].equalsIgnoreCase("setlevel")) {
            Integer newLevel = parseInteger(args[3]);
            if (newLevel == null || newLevel < 0
                    || (definition.maxLevel() >= 0 && newLevel > definition.maxLevel())) {
                messages.send(sender, "invalid-number");
                return true;
            }
            progress.mutate(target, definition, data -> LevelProgression.setLevel(data, newLevel))
                    .whenComplete((data, error) -> completeMutation(
                            sender, error, "level-set", target, definition, newLevel
                    ));
            return true;
        }

        Double amount = parseAmount(args[3]);
        if (amount == null) {
            messages.send(sender, "invalid-number");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "addxp" -> progress.mutate(target, definition, data -> LevelProgression.addExperience(
                    definition, data, progress.round(amount)
            )).whenComplete((data, error) -> completeMutation(
                    sender, error, "xp-added", target, definition, progress.round(amount)
            ));
            case "removexp" -> progress.mutate(target, definition, data -> data.withProgress(
                    data.level(), Math.max(0, progress.round(data.experience() - amount))
            )).whenComplete((data, error) -> completeMutation(
                    sender, error, "xp-removed", target, definition, progress.round(amount)
            ));
            case "setxp" -> progress.mutate(target, definition, data -> LevelProgression.setExperience(
                    definition, data, progress.round(amount)
            )).whenComplete((data, error) -> completeMutation(
                    sender, error, "xp-set", target, definition, progress.round(amount)
            ));
            default -> messages.send(sender, "invalid-command", Map.of(
                    "usage", "/televeling <addxp|removexp|setxp|setlevel|reset> <jugador> <nivel> [cantidad]"
            ));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(List.of("help", "reload", "addxp", "removexp", "setxp", "setlevel", "reset"), args[0]);
        }
        if (args.length == 2) {
            return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3) {
            return matching(new ArrayList<>(levels.all().keySet()), args[2]);
        }
        return List.of();
    }

    public boolean showLevel(CommandSender sender, LevelDefinition definition) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (!definition.permission().isBlank() && !player.hasPermission(definition.permission())) {
            messages.send(player, "level-permission");
            return true;
        }
        PlayerLevelData data = progress.get(player, definition);
        messages.send(player, "player-level", Map.of(
                "level_name", definition.name(),
                "level_display", text.formatLevel(definition, data, true, true),
                "xp", progress.formatNumber(data.experience()),
                "xp_required", progress.formatNumber(progress.requiredExperience(definition, data))
        ));
        return true;
    }

    public void reloadPluginFiles(CommandSender sender) {
        try {
            reloadConfig();
            mergeDefaults(getConfig(), resourceDefaults("config.yml"), new File(getDataFolder(), "config.yml"));
            reloadConfig();
            levels.reload();
            loadMessages();
            lineIcon = getConfig().getString("line-icon", "&8■");
            dynamicCommands.reload();
            messages.send(sender, "reloaded");
            getLogger().warning("Storage settings are read at startup; restart the server to apply storage changes.");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Could not reload TeLeveling files.", exception);
            messages.send(sender, "database-error");
        }
    }

    public LevelRegistry levels() {
        return levels;
    }

    public ProgressService progress() {
        return progress;
    }

    public TextFormatter text() {
        return text;
    }

    public MessageService messages() {
        return messages;
    }

    public String lineIcon() {
        return lineIcon;
    }

    public boolean isPlaceholderApiAvailable() {
        return placeholderApiAvailable;
    }

    private void completeMutation(
            CommandSender sender,
            Throwable error,
            String message,
            Player target,
            LevelDefinition definition,
            double amount
    ) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                getLogger().log(Level.SEVERE, "Could not update " + target.getName() + "'s level data.", error);
                messages.send(sender, "database-error");
                return;
            }
            messages.send(sender, message, Map.of(
                    "player", target.getName(),
                    "level", definition.name(),
                    "amount", progress.formatNumber(amount)
            ));
        });
    }

    private void setupPlaceholderApi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        placeholderExpansion = new TeLevelingExpansion(this);
        placeholderApiAvailable = placeholderExpansion.register();
        if (!placeholderApiAvailable) {
            getLogger().warning("Could not register the PlaceholderAPI expansion.");
        }
    }

    private void prepareConfigurationFiles() throws IOException {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder.");
        }
        saveDefaultConfig();
        File configFile = new File(getDataFolder(), "config.yml");
        mergeDefaults(getConfig(), resourceDefaults("config.yml"), configFile);
        reloadConfig();

        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
    }

    private void loadMessages() throws IOException {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(messagesFile);
        loaded.addDefaults(resourceDefaults("messages.yml"));
        loaded.options().copyDefaults(true);
        loaded.save(messagesFile);
        messages.setMessages(loaded);
    }

    private YamlConfiguration resourceDefaults(String name) throws IOException {
        InputStream input = getResource(name);
        if (input == null) {
            throw new IOException("Missing default resource " + name);
        }
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(reader);
            return defaults;
        } catch (org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IOException("Invalid default YAML resource " + name, exception);
        }
    }

    private void mergeDefaults(FileConfiguration existing, YamlConfiguration defaults, File target)
            throws IOException {
        existing.addDefaults(defaults);
        existing.options().copyDefaults(true);
        existing.save(target);
    }

    private static Double parseAmount(String input) {
        try {
            double amount = Double.parseDouble(input);
            return Double.isFinite(amount) && amount >= 0 ? amount : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer parseInteger(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static List<String> matching(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
