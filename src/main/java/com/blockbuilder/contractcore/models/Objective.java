package com.blockbuilder.contractcore.models;

import java.util.Locale;

public record Objective(ObjectiveType type, String target, int amount) {

    public String describe() {
        String target = this.target.toLowerCase(Locale.ROOT).replace('_', ' ');
        return switch (type) {
            case BREAK_BLOCK -> "Break " + amount + " " + target;
            case KILL_MOB -> "Kill " + amount + " " + target;
            case COLLECT_ITEM -> "Collect " + amount + " " + target;
        };
    }
}
