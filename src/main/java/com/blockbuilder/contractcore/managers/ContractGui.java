package com.blockbuilder.contractcore.managers;

import com.blockbuilder.contractcore.models.AcceptResult;
import com.blockbuilder.contractcore.models.ClaimResult;
import com.blockbuilder.contractcore.models.Contract;
import com.blockbuilder.contractcore.models.ContractProgress;
import com.blockbuilder.contractcore.models.Difficulty;
import com.blockbuilder.contractcore.models.ObjectiveType;
import com.blockbuilder.contractcore.models.Status;
import com.blockbuilder.contractcore.utils.Keys;
import com.blockbuilder.contractcore.utils.Messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ContractGui implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int BROWSE_SLOTS = 45;
    private static final Material FILLER = Material.GRAY_STAINED_GLASS_PANE;

    private final ContractService service;
    private final ContractRegistry registry;
    private final Keys keys;
    private final UUID owner;
    private boolean browse = true;
    private int page;
    private Inventory inventory;

    private ContractGui(ContractService service, ContractRegistry registry, Keys keys, UUID owner) {
        this.service = service;
        this.registry = registry;
        this.keys = keys;
        this.owner = owner;
    }

    public static void open(Player player, ContractService service, ContractRegistry registry, Keys keys) {
        ContractGui gui = new ContractGui(service, registry, keys, player.getUniqueId());
        gui.refresh(player);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Called from the GuiListener on every click inside this inventory. All
     * actions are validated by the service again; the button is only a hint.
     */
    public void onClick(InventoryClickEvent event) {
        if (event.getClickedInventory() != inventory) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }
        String action = meta.getPersistentDataContainer().get(keys.action(), PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (action.equals("browse")) {
            browse = true;
            page = 0;
            refresh(player);
            return;
        }
        if (action.equals("active")) {
            browse = false;
            refresh(player);
            return;
        }
        if (action.startsWith("page:")) {
            browse = true;
            page = Integer.parseInt(action.substring("page:".length()));
            refresh(player);
            return;
        }
        String id = action.substring(action.indexOf(':') + 1);
        if (action.startsWith("accept:")) {
            AcceptResult result = service.accept(player.getUniqueId(), id);
            service.reportAccept(player, result, id);
        } else if (action.startsWith("claim:")) {
            ClaimResult result = service.claim(player.getUniqueId(), id);
            service.reportClaim(player, result, id);
        } else if (action.startsWith("abandon:")) {
            boolean abandoned = service.abandon(player.getUniqueId(), id);
            service.reportAbandon(player, abandoned, id);
        }
        refresh(player);
    }

    private void refresh(Player player) {
        rebuild();
        player.openInventory(inventory);
    }

    private void rebuild() {
        inventory = Bukkit.createInventory(this, SIZE, title());
        inventory.setItem(0, button("browse", Material.EMERALD, "Browse contracts"));
        inventory.setItem(1, button("active", Material.BOOK, "Your contracts"));
        for (int i = 2; i <= 8; i++) {
            inventory.setItem(i, new ItemStack(FILLER));
        }
        if (browse) {
            buildBrowse();
        } else {
            buildActive();
        }
    }

    private void buildBrowse() {
        List<Contract> contracts = registry.enabled();
        int pages = Math.max(1, (contracts.size() + BROWSE_SLOTS - 1) / BROWSE_SLOTS);
        page = Math.min(page, pages - 1);
        int from = page * BROWSE_SLOTS;
        int to = Math.min(from + BROWSE_SLOTS, contracts.size());
        for (int i = from; i < to; i++) {
            inventory.setItem(9 + (i - from), browseItem(contracts.get(i)));
        }
        inventory.setItem(3, navButton("page:" + (page - 1), "Previous page", page > 0));
        inventory.setItem(5, navButton("page:" + (page + 1), "Next page", page < pages - 1));
    }

    private void buildActive() {
        int slot = 9;
        for (Map.Entry<String, ContractProgress> entry : service.contracts(owner).entrySet()) {
            Contract contract = registry.get(entry.getKey()).orElse(null);
            ContractProgress progress = entry.getValue();
            if (contract == null || slot + 1 >= SIZE) {
                continue;
            }
            if (progress.status() != Status.ACTIVE && progress.status() != Status.COMPLETE) {
                continue;
            }
            inventory.setItem(slot, statusItem(contract, progress));
            if (progress.status() == Status.COMPLETE) {
                inventory.setItem(slot + 1, button("claim:" + contract.id(), Material.GOLD_INGOT, "Claim reward"));
            } else {
                inventory.setItem(slot + 1, button("abandon:" + contract.id(), Material.BARRIER, "Abandon"));
            }
            slot += 2;
        }
    }

    private ItemStack browseItem(Contract contract) {
        ItemStack item = new ItemStack(icon(contract));
        item.editMeta(meta -> {
            meta.displayName(Component.text(contract.displayName()).color(difficultyColor(contract.difficulty())));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(contract.description(), NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(Component.text(contract.objective().describe(), NamedTextColor.YELLOW));
            lore.add(Component.text("Reward: " + contract.reward().exp() + " XP levels", NamedTextColor.GREEN));
            if (contract.repeatable() && contract.cooldownSeconds() > 0) {
                lore.add(Component.text("Cooldown: " + Messages.cooldown(contract.cooldownSeconds() * 1000L), NamedTextColor.GRAY));
            }
            ContractProgress progress = service.contracts(owner).get(contract.id());
            if (progress != null && progress.cooldownUntil() > System.currentTimeMillis()) {
                lore.add(Component.text("Locked for " + Messages.cooldown(progress.cooldownUntil() - System.currentTimeMillis()), NamedTextColor.RED));
            }
            lore.add(Component.empty());
            lore.add(Component.text("Click to accept", NamedTextColor.AQUA));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(keys.action(), PersistentDataType.STRING, "accept:" + contract.id());
        });
        return item;
    }

    private ItemStack statusItem(Contract contract, ContractProgress progress) {
        ItemStack item = new ItemStack(icon(contract));
        item.editMeta(meta -> {
            meta.displayName(Component.text(contract.displayName()).color(difficultyColor(contract.difficulty())));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(contract.objective().describe(), NamedTextColor.YELLOW));
            lore.add(Component.text("Progress: " + progress.progress() + " / " + contract.objective().amount(), NamedTextColor.GRAY));
            lore.add(progress.status() == Status.COMPLETE
                    ? Component.text("Complete - claim your reward!", NamedTextColor.GREEN)
                    : Component.text("In progress", NamedTextColor.GRAY));
            meta.lore(lore);
        });
        return item;
    }

    private ItemStack button(String action, Material material, String label) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(label, NamedTextColor.GRAY));
            meta.getPersistentDataContainer().set(keys.action(), PersistentDataType.STRING, action);
        });
        return item;
    }

    private ItemStack navButton(String action, String label, boolean enabled) {
        return enabled ? button(action, Material.ARROW, label) : new ItemStack(FILLER);
    }

    private Component title() {
        return Component.text("Contracts - " + (browse ? "Browse" : "Your contracts"));
    }

    private static NamedTextColor difficultyColor(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> NamedTextColor.GREEN;
            case MEDIUM -> NamedTextColor.YELLOW;
            case HARD -> NamedTextColor.RED;
        };
    }

    private static Material icon(Contract contract) {
        if (contract.objective().type() == ObjectiveType.KILL_MOB) {
            Material egg = Material.matchMaterial(contract.objective().target() + "_SPAWN_EGG");
            return egg != null ? egg : Material.SPAWNER;
        }
        Material material = Material.matchMaterial(contract.objective().target());
        return material != null ? material : Material.PAPER;
    }
}
