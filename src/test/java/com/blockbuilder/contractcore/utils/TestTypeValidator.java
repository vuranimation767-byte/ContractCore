package com.blockbuilder.contractcore.utils;

import java.util.Locale;
import java.util.Set;

public final class TestTypeValidator implements TypeValidator {

    private static final Set<String> MATERIALS = Set.of("STONE", "OAK_LOG", "DIAMOND");
    private static final Set<String> ENTITIES = Set.of("ZOMBIE", "SKELETON");

    @Override
    public boolean isMaterial(String name) {
        return MATERIALS.contains(name.toUpperCase(Locale.ROOT));
    }

    @Override
    public boolean isEntity(String name) {
        return ENTITIES.contains(name.toUpperCase(Locale.ROOT));
    }
}
