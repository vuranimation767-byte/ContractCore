package com.blockbuilder.contractcore.listeners;

import com.blockbuilder.contractcore.managers.ContractGui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.Listener;

public final class GuiListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof ContractGui gui) {
            // Cancel every click while the menu is open: shift-clicks from the
            // player inventory would otherwise move items into the GUI and lose them.
            event.setCancelled(true);
            gui.onClick(event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ContractGui) {
            event.setCancelled(true);
        }
    }
}
