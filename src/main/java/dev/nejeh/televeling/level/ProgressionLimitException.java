package dev.nejeh.televeling.level;

public final class ProgressionLimitException extends IllegalArgumentException {
    public ProgressionLimitException() {
        super("Experience mutation exceeds the maximum level transitions per command.");
    }
}
