package com.blockbuilder.contractcore.managers;

import com.blockbuilder.contractcore.models.Reward;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class XpRewardHandler implements RewardHandler {

    @Override
    public void apply(UUID playerId, Reward reward) {
        if (reward.exp() <= 0) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.giveExpLevels(reward.exp());
        }
    }
}
