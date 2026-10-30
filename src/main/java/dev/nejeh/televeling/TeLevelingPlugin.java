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
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.scheduler.BukkitTask;
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
import java.util.function.UnaryOperator;
import java.util.logging.Level;

public final class TeLevelingPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private LevelRegistry levels;
    private DatabaseManager database;
    private ProgressService progress;
    private TextFormatter text;
    private MessageService messages;
    private DynamicCommandRegistrar dynamicCommands;
    private TeLevelingExpansion placeholderExpansion;
    private BukkitTask syncTask;
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

        scheduleStorageSynchronization();
        for (Player player : Bukkit.getOnlinePlayers()) {
            progress.loadPlayer(player);
        }
        getLogger().info("TeLeveling v" + getPluginMeta().getVersion() + " enabled with "
                + levels.all().size() + " configured level(s).");
    }

    @Override
    public void onDisable() {
        if (syncTask != null) {
            syncTask.cancel();
        }
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
        return executeRootCommand(sender, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return completeRootCommand(args);
    }

    public boolean executeRootCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            messages.send(sender, "help");
            return true;
        }
        if (!sender.hasPermission("televeling.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload") && args.length == 1) {
            reloadPluginFiles(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("reset") && args.length == 2) {
            LevelDefinition definition = levels.get(args[1]);
            if (definition == null) {
                messages.send(sender, "level-not-found", Map.of("level", args[1]));
                return true;
            }
            progress.resetAll(definition).whenComplete((count, error) -> runOnMainThread(() -> {
                if (error != null) {
                    getLogger().log(Level.SEVERE, "Could not reset all progress for " + definition.id(), error);
                    messages.send(sender, "database-error");
                    return;
                }
                messages.send(sender, "level-reset-all", Map.of(
                        "level", definition.name(),
                        "count", Integer.toString(count)
                ));
            }));
            return true;
        }
        messages.send(sender, "invalid-command", Map.of(
                "usage", "/televeling <reload|reset <nivel-id>>"
        ));
        return true;
    }

    public List<String> completeRootCommand(String[] args) {
        if (args.length == 1) {
            return matching(List.of("help", "reload", "reset"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            return matching(new ArrayList<>(levels.all().keySet()), args[1]);
        }
        return List.of();
    }

    public boolean executeLevelCommand(
            CommandSender sender,
            LevelDefinition definition,
            String[] args,
            String commandLabel
    ) {
        ParsedArguments parsed = parseArguments(args);
        if (parsed == null) {
            messages.send(sender, "invalid-command", Map.of(
                    "usage", "/" + commandLabel + " help"
            ));
            return true;
        }
        List<String> arguments = parsed.arguments();
        if (arguments.isEmpty()) {
            return showLevel(sender, definition);
        }
        String action = arguments.getFirst().toLowerCase(Locale.ROOT);
        if (action.equals("help") && arguments.size() == 1) {
            messages.send(sender, "level-help", Map.of("command", commandLabel));
            return true;
        }
        if (!sender.hasPermission("televeling.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }

        int expectedArguments = action.equals("reset") ? 2 : 3;
        if (!List.of("givexp", "givelevel", "setxp", "setlevel", "takexp", "takelevel", "reset")
                .contains(action) || arguments.size() != expectedArguments) {
            messages.send(sender, "invalid-command", Map.of(
                    "usage", "/" + commandLabel + " <help|givexp|givelevel|setxp|setlevel|takexp|takelevel|reset> ..."
            ));
            return true;
        }

        Player target = Bukkit.getPlayerExact(arguments.get(1));
        if (target == null) {
            messages.send(sender, "player-not-found");
            return true;
        }

        if (action.equals("reset")) {
            progress.mutate(
                    target,
                    definition,
                    current -> current.withProgress(0, 0),
                    !parsed.flags().silent()
            ).whenComplete((updated, error) -> completeLevelMutation(
                    sender, target, definition, action, parsed.flags(), 0, error
            ));
            return true;
        }

        String value = arguments.get(2);
        if (action.equals("setlevel") || action.equals("givelevel") || action.equals("takelevel")) {
            Integer amount = parseInteger(value);
            if (amount == null || amount < (action.equals("setlevel") ? 0 : 1)
                    || (action.equals("setlevel") && definition.maxLevel() >= 0
                    && amount > definition.maxLevel())) {
                messages.send(sender, "invalid-number");
                return true;
            }
            UnaryOperator<PlayerLevelData> mutation = switch (action) {
                case "setlevel" -> (UnaryOperator<PlayerLevelData>)
                        current -> LevelProgression.setLevel(current, amount);
                case "givelevel" -> current -> LevelProgression.addLevels(definition, current, amount);
                default -> current -> LevelProgression.removeLevels(current, amount);
            };
            progress.mutate(target, definition, mutation, !parsed.flags().silent())
                    .whenComplete((updated, error) -> completeLevelMutation(
                            sender, target, definition, action, parsed.flags(), amount, error
                    ));
            return true;
        }

        Double amount = parseAmount(value);
        if (amount == null || (amount <= 0 && !action.equals("setxp"))) {
            messages.send(sender, "invalid-number");
            return true;
        }
        double roundedAmount = progress.round(amount);
        UnaryOperator<PlayerLevelData> mutation = switch (action) {
            case "givexp" -> (UnaryOperator<PlayerLevelData>)
                    current -> LevelProgression.addExperience(definition, current, roundedAmount);
            case "setxp" -> current -> LevelProgression.setExperience(definition, current, roundedAmount);
            default -> current -> LevelProgression.removeExperience(definition, current, roundedAmount);
        };
        progress.mutate(target, definition, mutation, !parsed.flags().silent())
                .whenComplete((updated, error) -> completeLevelMutation(
                        sender, target, definition, action, parsed.flags(), roundedAmount, error
                ));
        return true;
    }

    public List<String> completeLevelCommand(
            CommandSender sender,
            LevelDefinition definition,
            String[] args
    ) {
        if (args.length == 1) {
            return matching(List.of("help", "givexp", "givelevel", "setxp", "setlevel",
                    "takexp", "takelevel", "reset", "-s", "-f"), args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("help")) {
            return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length >= 3 && !args[0].equalsIgnoreCase("help")) {
            return matching(List.of("-s", "-f"), args[args.length - 1]);
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

            DatabaseManager updatedDatabase = new DatabaseManager(this, getConfig());
            if (database != null) {
                database.close();
            }
            database = updatedDatabase;
            progress = new ProgressService(this, database, levels);
            dynamicCommands.reload();
            scheduleStorageSynchronization();
            for (Player player : Bukkit.getOnlinePlayers()) {
                progress.loadPlayer(player);
            }
            messages.send(sender, "reloaded");
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

    private void completeLevelMutation(
            CommandSender sender,
            Player target,
            LevelDefinition definition,
            String action,
            CommandFlags flags,
            double amount,
            Throwable error
    ) {
        runOnMainThread(() -> {
            if (error != null) {
                getLogger().log(Level.SEVERE, "Could not update " + target.getName() + "'s level data.", error);
                messages.send(sender, "database-error");
                return;
            }

            Map<String, String> values = Map.of(
                    "player", target.getName(),
                    "level", definition.name(),
                    "amount", progress.formatNumber(amount)
            );
            String feedbackKey = switch (action) {
                case "givexp" -> "command-xp-given";
                case "givelevel" -> "command-level-given";
                case "setxp" -> "command-xp-set";
                case "setlevel" -> "command-level-set";
                case "takexp" -> "command-xp-taken";
                case "takelevel" -> "command-level-taken";
                default -> "command-player-reset";
            };
            String targetKey = switch (action) {
                case "givexp" -> "target-xp-given";
                case "givelevel" -> "target-level-given";
                case "setxp" -> "target-xp-set";
                case "setlevel" -> "target-level-set";
                case "takexp" -> "target-xp-taken";
                case "takelevel" -> "target-level-taken";
                default -> "target-player-reset";
            };

            if (!flags.noFeedback()) {
                messages.send(sender, feedbackKey, values);
            }
            if (!flags.silent() && target.isOnline() && (target != sender || flags.noFeedback())) {
                messages.send(target, targetKey, values);
            }
        });
    }

    private void scheduleStorageSynchronization() {
        if (syncTask != null) {
            syncTask.cancel();
        }
        long interval = Math.max(1, getConfig().getLong("save-time", 60));
        syncTask = Bukkit.getScheduler().runTaskTimer(
                this,
                progress::refreshOnlinePlayers,
                interval * 20,
                interval * 20
        );
    }

    private void runOnMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, task);
        }
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

    private static ParsedArguments parseArguments(String[] input) {
        List<String> arguments = new ArrayList<>();
        boolean silent = false;
        boolean noFeedback = false;
        for (String value : input) {
            switch (value.toLowerCase(Locale.ROOT)) {
                case "-s" -> silent = true;
                case "-f" -> noFeedback = true;
                default -> {
                    if (value.startsWith("-")) {
                        return null;
                    }
                    arguments.add(value);
                }
            }
        }
        return new ParsedArguments(List.copyOf(arguments), new CommandFlags(silent, noFeedback));
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

    private record CommandFlags(boolean silent, boolean noFeedback) {
    }

    private record ParsedArguments(List<String> arguments, CommandFlags flags) {
    }
}
