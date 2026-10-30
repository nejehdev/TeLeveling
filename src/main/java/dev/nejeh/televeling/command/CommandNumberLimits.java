package dev.nejeh.televeling.command;

import java.util.regex.Pattern;
import java.util.function.Consumer;

public final class CommandNumberLimits {
    public static final int MAX_LEVEL_AMOUNT = 10_000;
    public static final double MAX_EXPERIENCE_AMOUNT = 1_000_000;
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[0-9]+");
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");

    private CommandNumberLimits() {
    }

    public static Integer parseLevel(String input, int configuredMaximum) {
        if (!POSITIVE_INTEGER.matcher(input).matches()) {
            return null;
        }
        try {
            int amount = Integer.parseInt(input);
            return amount >= 1 && amount <= Math.min(configuredMaximum, MAX_LEVEL_AMOUNT) ? amount : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static Double parseExperience(String input, double configuredMaximum) {
        if (!POSITIVE_DECIMAL.matcher(input).matches()) {
            return null;
        }
        try {
            double amount = Double.parseDouble(input);
            return Double.isFinite(amount) && amount >= 1
                    && amount <= Math.min(configuredMaximum, MAX_EXPERIENCE_AMOUNT)
                    ? amount : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static double normalizeConfiguredMaximum(
            double configuredMaximum,
            double internalMaximum,
            String setting,
            Consumer<String> warning
    ) {
        if (!Double.isFinite(configuredMaximum) || configuredMaximum < 1) {
            warning.accept(setting + " must be a positive number; using the internal maximum "
                    + internalMaximum + ".");
            return internalMaximum;
        }
        if (configuredMaximum > internalMaximum) {
            warning.accept(setting + " exceeds the internal maximum " + internalMaximum
                    + "; clamping it to that maximum.");
            return internalMaximum;
        }
        return configuredMaximum;
    }
}
