package com.blockbuilder.contractcore.managers;

import com.blockbuilder.contractcore.models.AcceptResult;
import com.blockbuilder.contractcore.models.ClaimResult;
import com.blockbuilder.contractcore.models.Contract;
import com.blockbuilder.contractcore.models.ContractProgress;
import com.blockbuilder.contractcore.models.ObjectiveType;
import com.blockbuilder.contractcore.models.PlayerData;
import com.blockbuilder.contractcore.models.Status;
import com.blockbuilder.contractcore.utils.Messages;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the in-memory player state and the contract state machine. All mutating
 * methods are called from the server main thread; anything that has to leave it
 * (database calls, async continuations) comes back through the injected
 * {@link Dispatcher}.
 */
public final class ContractService {

    @FunctionalInterface
    public interface Dispatcher {
        void run(Runnable task);
    }

    @FunctionalInterface
    public interface Notifier {
        void send(UUID player, String template, Object... args);
    }

    private final ContractRegistry registry;
    private final PlayerDataStore store;
    private final RewardHandler rewards;
    private final Dispatcher main;
    private final Notifier notify;
    private final Logger logger;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final Set<String> claiming = ConcurrentHashMap.newKeySet();

    public ContractService(ContractRegistry registry, PlayerDataStore store, RewardHandler rewards,
                           Dispatcher main, Notifier notify, Logger logger) {
        this.registry = registry;
        this.store = store;
        this.rewards = rewards;
        this.main = main;
        this.notify = notify;
        this.logger = logger;
    }

    public CompletableFuture<Void> loadPlayer(UUID uuid) {
        players.put(uuid, PlayerData.loading(uuid));
        return store.load(uuid)
                .thenAccept(data -> main.run(() ->
                        players.computeIfPresent(uuid, (id, current) -> current.loaded() ? current : data)))
                .exceptionally(error -> {
                    // Leave no half-loaded placeholder behind; the next join retries.
                    logger.log(Level.WARNING, "Could not load contract data for " + uuid, error);
                    main.run(() -> players.remove(uuid));
                    return null;
                });
    }

    public CompletableFuture<Void> unloadPlayer(UUID uuid) {
        PlayerData data = players.remove(uuid);
        boolean inFlight = hasInFlightClaim(uuid);
        String prefix = uuid + ":";
        claiming.removeIf(marker -> marker.startsWith(prefix));
        if (data == null || !data.loaded() || inFlight) {
            return CompletableFuture.completedFuture(null);
        }
        store.dirty().remove(uuid);
        return store.save(data);
    }

    public Map<String, ContractProgress> contracts(UUID uuid) {
        PlayerData data = players.get(uuid);
        return data == null || !data.loaded() ? Map.of() : data.contracts();
    }

    public boolean hasActiveType(UUID uuid, ObjectiveType type) {
        PlayerData data = players.get(uuid);
        if (data == null || !data.loaded()) {
            return false;
        }
        for (Map.Entry<String, ContractProgress> entry : data.contracts().entrySet()) {
            if (entry.getValue().status() != Status.ACTIVE) {
                continue;
            }
            Contract contract = registry.get(entry.getKey()).orElse(null);
            if (contract != null && contract.objective().type() == type) {
                return true;
            }
        }
        return false;
    }

    public AcceptResult accept(UUID uuid, String contractId) {
        PlayerData data = players.get(uuid);
        if (data == null || !data.loaded()) {
            return AcceptResult.NOT_LOADED;
        }
        Contract contract = registry.get(contractId).orElse(null);
        if (contract == null) {
            return AcceptResult.NOT_FOUND;
        }
        if (!contract.enabled()) {
            return AcceptResult.DISABLED;
        }
        long now = System.currentTimeMillis();
        ContractProgress existing = data.contracts().get(contract.id());
        if (existing != null) {
            if (existing.status() == Status.ACTIVE || existing.status() == Status.COMPLETE) {
                return AcceptResult.ALREADY_ACTIVE;
            }
            if (existing.status() == Status.CLAIMED && !contract.repeatable()) {
                return AcceptResult.NOT_REPEATABLE;
            }
            if (existing.cooldownUntil() > now) {
                return AcceptResult.COOLDOWN;
            }
        }
        if (countActive(data) >= registry.settings().maxActiveContracts()) {
            return AcceptResult.LIMIT;
        }
        ContractProgress started = existing == null
                ? new ContractProgress(0, Status.ACTIVE, now, 0L, 0L, 0L, 0)
                : existing.accept(now);
        PlayerData updated = data.update(contract.id(), started);
        players.put(uuid, updated);
        store.dirty().add(uuid);
        if (hasInFlightClaim(uuid)) {
            // Defer the snapshot write so it cannot race an in-flight claim transition;
            // the dirty marker makes the autosave pick this up later.
            return AcceptResult.OK;
        }
        store.save(updated);
        return AcceptResult.OK;
    }

    public ClaimResult claim(UUID uuid, String contractId) {
        PlayerData data = players.get(uuid);
        if (data == null || !data.loaded()) {
            return ClaimResult.NOT_LOADED;
        }
        Contract contract = registry.get(contractId).orElse(null);
        if (contract == null) {
            return ClaimResult.NOT_FOUND;
        }
        ContractProgress progress = data.contracts().get(contract.id());
        if (progress == null || progress.status() != Status.COMPLETE) {
            return ClaimResult.NOT_COMPLETE;
        }
        String marker = uuid + ":" + contract.id();
        if (!claiming.add(marker)) {
            return ClaimResult.IN_PROGRESS;
        }
        long now = System.currentTimeMillis();
        long cooldownUntil = contract.repeatable() ? now + contract.cooldownSeconds() * 1000L : 0L;
        store.claimTransition(uuid, contract.id(), contract.objective().amount(), now, cooldownUntil)
                .whenComplete((claimed, error) -> main.run(() ->
                        finishClaim(uuid, contract, progress, claimed, error, now, cooldownUntil, marker)));
        return ClaimResult.OK;
    }

    private void finishClaim(UUID uuid, Contract contract, ContractProgress progress, Boolean claimed,
                             Throwable error, long now, long cooldownUntil, String marker) {
        claiming.remove(marker);
        PlayerData data = players.get(uuid);
        if (error != null) {
            logger.log(Level.SEVERE, "Claim transition failed for " + uuid + " / " + contract.id(), error);
            return;
        }
        if (data == null) {
            logger.warning("Claim finalized for offline player " + uuid + " / " + contract.id()
                    + "; the reward was not delivered");
            return;
        }
        if (claimed) {
            players.put(uuid, data.update(contract.id(), progress.claim(now, cooldownUntil)));
            rewards.apply(uuid, contract.reward());
            notify.send(uuid, Messages.CLAIMED, contract.displayName(), contract.reward().exp());
        } else {
            // Lost the transition race; resynchronize the row from the database.
            store.loadOne(uuid, contract.id()).whenComplete((row, rowError) -> main.run(() -> {
                PlayerData latest = players.get(uuid);
                if (latest != null && rowError == null) {
                    players.put(uuid, latest.update(contract.id(), row.orElse(null)));
                }
            }));
            notify.send(uuid, Messages.ALREADY_CLAIMED);
        }
    }

    public boolean abandon(UUID uuid, String contractId) {
        PlayerData data = players.get(uuid);
        if (data == null || !data.loaded()) {
            return false;
        }
        Contract contract = registry.get(contractId).orElse(null);
        if (contract == null) {
            return false;
        }
        ContractProgress progress = data.contracts().get(contract.id());
        if (progress == null || (progress.status() != Status.ACTIVE && progress.status() != Status.COMPLETE)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long cooldownUntil = contract.repeatable() ? now + contract.cooldownSeconds() * 1000L : 0L;
        players.put(uuid, data.update(contract.id(), progress.abandon(cooldownUntil)));
        store.dirty().add(uuid);
        return true;
    }

    public void progress(UUID uuid, ObjectiveType type, String target, int amount) {
        PlayerData data = players.get(uuid);
        if (data == null || !data.loaded()) {
            return;
        }
        boolean changed = false;
        boolean completed = false;
        for (Map.Entry<String, ContractProgress> entry : data.contracts().entrySet()) {
            ContractProgress progress = entry.getValue();
            if (progress.status() != Status.ACTIVE) {
                continue;
            }
            Contract contract = registry.get(entry.getKey()).orElse(null);
            if (contract == null || contract.objective().type() != type) {
                continue;
            }
            if (!contract.objective().target().equalsIgnoreCase(target)) {
                continue;
            }
            progress = progress.add(amount);
            if (progress.progress() >= contract.objective().amount()) {
                progress = progress.complete(System.currentTimeMillis());
                completed = true;
            }
            data = data.update(entry.getKey(), progress);
            changed = true;
        }
        if (!changed) {
            return;
        }
        players.put(uuid, data);
        store.dirty().add(uuid);
        if (completed) {
            notify.send(uuid, Messages.OBJECTIVE_COMPLETE);
        }
    }

    public boolean adminAssign(UUID uuid, String contractId) {
        PlayerData data = players.get(uuid);
        if (data == null || !data.loaded()) {
            return false;
        }
        Contract contract = registry.get(contractId).orElse(null);
        if (contract == null) {
            return false;
        }
        ContractProgress existing = data.contracts().get(contract.id());
        ContractProgress assigned = new ContractProgress(0, Status.ACTIVE, System.currentTimeMillis(),
                0L, 0L, 0L, existing == null ? 0 : existing.completedCount());
        PlayerData updated = data.update(contract.id(), assigned);
        players.put(uuid, updated);
        persist(updated);
        return true;
    }

    public boolean adminComplete(UUID uuid, String contractId) {
        PlayerData data = players.get(uuid);
        if (data == null || !data.loaded()) {
            return false;
        }
        Contract contract = registry.get(contractId).orElse(null);
        if (contract == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        ContractProgress existing = data.contracts().get(contract.id());
        ContractProgress base = existing == null
                ? new ContractProgress(0, Status.ACTIVE, now, 0L, 0L, 0L, 0)
                : existing;
        ContractProgress completed = new ContractProgress(contract.objective().amount(), Status.COMPLETE,
                base.acceptedAt(), now, base.claimedAt(), base.cooldownUntil(), base.completedCount());
        PlayerData updated = data.update(contract.id(), completed);
        players.put(uuid, updated);
        persist(updated);
        return true;
    }

    public boolean adminReset(UUID uuid, String contractId) {
        PlayerData data = players.get(uuid);
        if (data == null || !data.loaded()) {
            return false;
        }
        PlayerData updated = contractId == null ? data.cleared() : data.update(contractId, null);
        players.put(uuid, updated);
        persist(updated);
        return true;
    }

    private void persist(PlayerData data) {
        store.dirty().add(data.uuid());
        if (!hasInFlightClaim(data.uuid())) {
            store.save(data);
        }
    }

    /**
     * Writes every dirty player to the database. Safe to call from any thread:
     * snapshots are immutable records and the database owns its own thread.
     */
    public CompletableFuture<Void> flushDirty() {
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (UUID uuid : new ArrayList<>(store.dirty())) {
            if (!store.dirty().remove(uuid)) {
                continue;
            }
            PlayerData data = players.get(uuid);
            if (data == null || !data.loaded() || hasInFlightClaim(uuid)) {
                continue;
            }
            saves.add(store.save(data));
        }
        return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
    }

    private boolean hasInFlightClaim(UUID uuid) {
        String prefix = uuid + ":";
        for (String marker : claiming) {
            if (marker.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int countActive(PlayerData data) {
        int count = 0;
        for (ContractProgress progress : data.contracts().values()) {
            if (progress.status() == Status.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    public void reportAccept(Player player, AcceptResult result, String contractId) {
        UUID uuid = player.getUniqueId();
        switch (result) {
            case OK -> {
                Contract contract = registry.get(contractId).orElse(null);
                if (contract != null) {
                    notify.send(uuid, Messages.ACCEPTED, contract.displayName());
                }
            }
            case NOT_FOUND -> notify.send(uuid, Messages.CONTRACT_NOT_FOUND, contractId);
            case DISABLED -> notify.send(uuid, Messages.CONTRACT_DISABLED);
            case ALREADY_ACTIVE -> notify.send(uuid, Messages.ALREADY_ACTIVE, displayName(contractId));
            case NOT_REPEATABLE -> notify.send(uuid, Messages.NOT_REPEATABLE, displayName(contractId));
            case COOLDOWN -> {
                ContractProgress progress = contracts(uuid).get(contractId);
                long remaining = progress == null ? 0 : progress.cooldownUntil() - System.currentTimeMillis();
                notify.send(uuid, Messages.CONTRACT_COOLDOWN, Messages.cooldown(remaining), displayName(contractId));
            }
            case LIMIT -> notify.send(uuid, Messages.ACTIVE_LIMIT, registry.settings().maxActiveContracts());
            case NOT_LOADED -> notify.send(uuid, Messages.PROFILE_LOADING);
        }
    }

    public void reportClaim(Player player, ClaimResult result, String contractId) {
        switch (result) {
            case OK -> {
                // Success is reported asynchronously by finishClaim once rewards are applied.
            }
            case NOT_FOUND -> notify.send(player.getUniqueId(), Messages.CONTRACT_NOT_FOUND, contractId);
            case NOT_COMPLETE -> notify.send(player.getUniqueId(), Messages.NOT_COMPLETE);
            case IN_PROGRESS -> notify.send(player.getUniqueId(), Messages.CLAIM_IN_PROGRESS);
            case NOT_LOADED -> notify.send(player.getUniqueId(), Messages.PROFILE_LOADING);
        }
    }

    public void reportAbandon(Player player, boolean abandoned, String contractId) {
        if (abandoned) {
            notify.send(player.getUniqueId(), Messages.ABANDONED, displayName(contractId));
        } else {
            notify.send(player.getUniqueId(), Messages.NOT_COMPLETE);
        }
    }

    private String displayName(String contractId) {
        return registry.get(contractId).map(Contract::displayName).orElse(contractId);
    }
}
