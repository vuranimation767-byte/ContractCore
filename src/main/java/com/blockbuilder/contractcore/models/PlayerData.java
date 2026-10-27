package com.blockbuilder.contractcore.models;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record PlayerData(UUID uuid, Map<String, ContractProgress> contracts, boolean loaded) {

    public static PlayerData empty(UUID uuid) {
        return new PlayerData(uuid, new HashMap<>(), true);
    }

    public static PlayerData loading(UUID uuid) {
        return new PlayerData(uuid, Map.of(), false);
    }

    public PlayerData update(String contractId, ContractProgress progress) {
        Map<String, ContractProgress> copy = new HashMap<>(contracts);
        if (progress == null) {
            copy.remove(contractId);
        } else {
            copy.put(contractId, progress);
        }
        return new PlayerData(uuid, copy, loaded);
    }

    public PlayerData cleared() {
        return new PlayerData(uuid, new HashMap<>(), loaded);
    }
}
