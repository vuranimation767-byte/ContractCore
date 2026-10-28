package com.blockbuilder.contractcore.listeners;

import com.blockbuilder.contractcore.managers.ContractService;
import com.blockbuilder.contractcore.models.ObjectiveType;
import com.blockbuilder.contractcore.utils.Keys;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Credits objective progress from gameplay events. Every handler exits early
 * when the player has no matching active contract, so the common case is a
 * single set lookup.
 *
 * Anti-farm measures:
 *  - blocks a player placed themselves never count when they break them;
 *  - mob kills are credited to the player who dealt the damage, not the one
 *    who happened to be nearby, and spawn-egg/command spawns never count;
 *  - items a player dropped themselves never count, and each item entity can
 *    be counted at most once so failed pickups cannot be replayed.
 */
public final class ObjectiveListener implements Listener {

    private static final int MAX_TRACKED_PLACEMENTS = 2048;

    private final ContractService service;
    private final Keys keys;
    private final Map<UUID, Set<Long>> placedBlocks = new HashMap<>();

    public ObjectiveListener(ContractService service, Keys keys) {
        this.service = service;
        this.keys = keys;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!service.hasActiveType(uuid, ObjectiveType.BREAK_BLOCK)) {
            return;
        }
        Set<Long> placed = placedBlocks.get(uuid);
        if (placed != null && placed.remove(pack(event.getBlock()))) {
            return;
        }
        service.progress(uuid, ObjectiveType.BREAK_BLOCK, event.getBlock().getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!service.hasActiveType(uuid, ObjectiveType.BREAK_BLOCK)) {
            return;
        }
        Set<Long> placed = placedBlocks.computeIfAbsent(uuid, id -> new LinkedHashSet<>());
        placed.add(pack(event.getBlock()));
        while (placed.size() > MAX_TRACKED_PLACEMENTS) {
            placed.remove(placed.iterator().next());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || !service.hasActiveType(attacker.getUniqueId(), ObjectiveType.KILL_MOB)) {
            return;
        }
        victim.getPersistentDataContainer().set(keys.damageCredit(), PersistentDataType.STRING, attacker.getUniqueId().toString());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        if (mob instanceof Player) {
            return;
        }
        Player killer = mob.getKiller();
        String credited = killer != null
                ? killer.getUniqueId().toString()
                : mob.getPersistentDataContainer().get(keys.damageCredit(), PersistentDataType.STRING);
        if (credited == null) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(credited);
        } catch (IllegalArgumentException malformed) {
            return;
        }
        if (!service.hasActiveType(uuid, ObjectiveType.KILL_MOB)) {
            return;
        }
        SpawnReason reason = mob.getEntitySpawnReason();
        if (reason == SpawnReason.SPAWNER_EGG || reason == SpawnReason.COMMAND) {
            return;
        }
        service.progress(uuid, ObjectiveType.KILL_MOB, mob.getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!service.hasActiveType(uuid, ObjectiveType.COLLECT_ITEM)) {
            return;
        }
        Item item = event.getItem();
        String self = uuid.toString();
        if (item.getPersistentDataContainer().has(keys.counted(), PersistentDataType.STRING)
                || self.equals(item.getPersistentDataContainer().get(keys.droppedBy(), PersistentDataType.STRING))
                || self.equals(stackTag(item.getItemStack()))) {
            return;
        }
        item.getPersistentDataContainer().set(keys.counted(), PersistentDataType.STRING, self);
        service.progress(uuid, ObjectiveType.COLLECT_ITEM, item.getItemStack().getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        String self = event.getPlayer().getUniqueId().toString();
        Item item = event.getItemDrop();
        item.getPersistentDataContainer().set(keys.droppedBy(), PersistentDataType.STRING, self);
        item.getItemStack().editMeta(meta ->
                meta.getPersistentDataContainer().set(keys.droppedBy(), PersistentDataType.STRING, self));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        placedBlocks.remove(event.getPlayer().getUniqueId());
    }

    private String stackTag(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(keys.droppedBy(), PersistentDataType.STRING);
    }

    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private static long pack(Block block) {
        return ((long) block.getX() << 38) | (((long) block.getY() & 0xFFFL) << 26) | (block.getZ() & 0x3FFFFFFL);
    }
}
