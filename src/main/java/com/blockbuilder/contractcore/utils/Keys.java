package com.blockbuilder.contractcore.utils;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

public final class Keys {

    private final NamespacedKey action;
    private final NamespacedKey droppedBy;
    private final NamespacedKey damageCredit;
    private final NamespacedKey counted;

    public Keys(Plugin plugin) {
        this.action = new NamespacedKey(plugin, "action");
        this.droppedBy = new NamespacedKey(plugin, "dropped_by");
        this.damageCredit = new NamespacedKey(plugin, "damage_credit");
        this.counted = new NamespacedKey(plugin, "counted");
    }

    public NamespacedKey action() {
        return action;
    }

    public NamespacedKey droppedBy() {
        return droppedBy;
    }

    public NamespacedKey damageCredit() {
        return damageCredit;
    }

    public NamespacedKey counted() {
        return counted;
    }
}
