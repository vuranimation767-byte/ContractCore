package com.blockbuilder.contractcore.managers;

import com.blockbuilder.contractcore.models.Reward;

import java.util.UUID;

public interface RewardHandler {

    /**
     * Delivers a contract reward to the player. Implementations must run on the
     * server main thread.
     */
    void apply(UUID player, Reward reward);
}
