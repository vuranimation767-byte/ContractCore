package com.blockbuilder.contractcore.models;

public record Contract(
        String id,
        String displayName,
        String description,
        Objective objective,
        Difficulty difficulty,
        Reward reward,
        boolean enabled,
        boolean repeatable,
        int cooldownSeconds) {
}
