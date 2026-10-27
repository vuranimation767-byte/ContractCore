package com.blockbuilder.contractcore.utils;

import org.bukkit.entity.EntityType;
import org.bukkit.Material;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class ServerTypeValidator implements TypeValidator {

    private final Set<String> entityTypes = Arrays.stream(EntityType.values())
            .filter(type -> type.isAlive() && type != EntityType.PLAYER && type != EntityType.UNKNOWN)
            .map(EntityType::name)
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public boolean isMaterial(String name) {
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return material != null && material.isItem() && !material.isAir();
    }

    @Override
    public boolean isEntity(String name) {
        return entityTypes.contains(name.toUpperCase(Locale.ROOT));
    }
}
