package com.blockbuilder.contractcore.listeners;

import com.blockbuilder.contractcore.managers.ContractService;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerLifecycleListener implements Listener {

    private final ContractService service;

    public PlayerLifecycleListener(ContractService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        service.loadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.unloadPlayer(event.getPlayer().getUniqueId());
    }
}
