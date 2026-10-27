package com.blockbuilder.contractcore.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<gold>ContractCore<dark_gray>] <gray>";

    public static final String CONTRACT_NOT_FOUND = "Unknown contract id <white>%s</white>.";
    public static final String CONTRACT_DISABLED = "That contract is currently disabled.";
    public static final String ALREADY_ACTIVE = "You already have <white>%s</white> active.";
    public static final String ACTIVE_LIMIT = "You can only have <white>%d</white> active contracts at once.";
    public static final String CONTRACT_COOLDOWN = "You must wait <white>%s</white> before taking <white>%s</white> again.";
    public static final String NOT_REPEATABLE = "You have already completed <white>%s</white>.";
    public static final String PROFILE_LOADING = "Your profile is still loading, try again in a moment.";
    public static final String ACCEPTED = "Accepted <white>%s</white>. Progress updates as you play.";
    public static final String OBJECTIVE_COMPLETE = "<green>Contract objective complete!</green> Use <click:run_command:'/contracts'><white>/contracts</white></click> to claim your reward.";
    public static final String CLAIMED = "Claimed <white>%s</white>: <gold>%d XP levels</gold>.";
    public static final String NOT_COMPLETE = "That contract is not complete yet.";
    public static final String ALREADY_CLAIMED = "This reward has already been claimed.";
    public static final String CLAIM_IN_PROGRESS = "Your claim is already being processed.";
    public static final String ABANDONED = "Abandoned <white>%s</white>.";
    public static final String PROGRESS_HEADER = "<gold>Your contracts</gold>";
    public static final String PROGRESS_LINE = "<yellow>%s</yellow> <gray>- %d/%d %s</gray>";
    public static final String PROGRESS_EMPTY = "You have no contracts yet. Use /contracts to browse.";
    public static final String RELOADED = "Configuration reloaded: <white>%d</white> contracts loaded.";
    public static final String RELOAD_FAILED = "<red>Reload failed, keeping the previous configuration:</red>";
    public static final String ADMIN_ASSIGNED = "Assigned <white>%s</white> to <white>%s</white>.";
    public static final String ADMIN_COMPLETED = "Completed <white>%s</white> for <white>%s</white>.";
    public static final String ADMIN_RESET_ONE = "Reset <white>%s</white> for <white>%s</white>.";
    public static final String ADMIN_RESET_ALL = "Reset all contracts for <white>%s</white>.";
    public static final String ADMIN_INFO_NONE = "No stored contracts for <white>%s</white>.";
    public static final String ADMIN_INFO_LINE = "<yellow>%s</yellow> <gray>%s %d/%d</gray>";
    public static final String ADMIN_ACTION_BUSY = "That player has a claim in progress, try again in a moment.";
    public static final String SAVED = "Player data flushed to the database.";
    public static final String PLAYER_NOT_ONLINE = "No online player named <white>%s</white>.";
    public static final String PLAYER_ONLY = "This command can only be used by players.";
    public static final String USAGE_CONTRACTS = "Usage: /contracts [accept|claim|abandon <id>|progress]";
    public static final String USAGE_ADMIN = "Usage: /contractcore <reload|assign <player> <id>|complete <player> <id>|reset <player> [id]|info <player>|save>";

    private Messages() {
    }

    public static void send(CommandSender target, String template, Object... args) {
        target.sendMessage(MM.deserialize(PREFIX + String.format(template, args)));
    }

    public static void sendRaw(CommandSender target, String message) {
        target.sendMessage(MM.deserialize(message));
    }

    public static Component component(String template, Object... args) {
        return MM.deserialize(String.format(template, args));
    }

    public static String cooldown(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }
}
