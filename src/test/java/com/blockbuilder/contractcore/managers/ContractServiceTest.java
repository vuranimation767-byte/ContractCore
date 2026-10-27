package com.blockbuilder.contractcore.managers;

import com.blockbuilder.contractcore.managers.ContractRegistry;
import com.blockbuilder.contractcore.models.AcceptResult;
import com.blockbuilder.contractcore.models.ClaimResult;
import com.blockbuilder.contractcore.models.Contract;
import com.blockbuilder.contractcore.models.ContractConfig;
import com.blockbuilder.contractcore.models.ContractProgress;
import com.blockbuilder.contractcore.models.Difficulty;
import com.blockbuilder.contractcore.models.Objective;
import com.blockbuilder.contractcore.models.ObjectiveType;
import com.blockbuilder.contractcore.models.Reward;
import com.blockbuilder.contractcore.models.Status;
import com.blockbuilder.contractcore.utils.Messages;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.logging.Logger;
import java.util.UUID;

class ContractServiceTest {

    private static final Logger LOG = Logger.getLogger("contractcore-test");

    private final UUID playerId = UUID.randomUUID();
    private Path database;
    private PlayerDataStore store;
    private ContractService service;
    private RecordingRewards rewards;
    private List<String> notifications;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        database = dir.resolve("data").resolve("test.db");
        store = new PlayerDataStore(database, LOG);
        rewards = new RecordingRewards();
        notifications = new ArrayList<>();

        ContractRegistry registry = new ContractRegistry();
        registry.set(new ContractConfig(new ContractConfig.Settings(2, 20), List.of(
                contract("a", ObjectiveType.BREAK_BLOCK, "STONE", 5, false, 0, 100),
                contract("b", ObjectiveType.BREAK_BLOCK, "OAK_LOG", 3, true, 3600, 200),
                contract("c", ObjectiveType.KILL_MOB, "ZOMBIE", 2, false, 0, 50),
                contract("off", ObjectiveType.COLLECT_ITEM, "DIAMOND", 1, false, 0, 10, false))));

        service = new ContractService(registry, store, rewards, Runnable::run,
                (uuid, template, args) -> notifications.add(template), LOG);
        service.loadPlayer(playerId).get();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static Contract contract(String id, ObjectiveType type, String target, int amount,
                                     boolean repeatable, int cooldown, int exp) {
        return contract(id, type, target, amount, repeatable, cooldown, exp, true);
    }

    private static Contract contract(String id, ObjectiveType type, String target, int amount,
                                     boolean repeatable, int cooldown, int exp, boolean enabled) {
        return new Contract(id, id, "", new Objective(type, target, amount), Difficulty.EASY,
                new Reward(exp), enabled, repeatable, cooldown);
    }

    private void await(BooleanSupplier condition) {
        for (int i = 0; i < 500 && !condition.getAsBoolean(); i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue(condition.getAsBoolean(), "condition not met in time");
    }

    private ContractProgress progress(String id) {
        return service.contracts(playerId).get(id);
    }

    @Test
    void acceptStartsContractActive() {
        assertEquals(AcceptResult.OK, service.accept(playerId, "a"));
        assertEquals(Status.ACTIVE, progress("a").status());
        assertEquals(0, progress("a").progress());
    }

    @Test
    void acceptRespectsActiveLimit() {
        assertEquals(AcceptResult.OK, service.accept(playerId, "a"));
        assertEquals(AcceptResult.OK, service.accept(playerId, "b"));
        assertEquals(AcceptResult.LIMIT, service.accept(playerId, "c"));
    }

    @Test
    void duplicateAcceptRejected() {
        service.accept(playerId, "a");
        assertEquals(AcceptResult.ALREADY_ACTIVE, service.accept(playerId, "a"));
    }

    @Test
    void disabledContractRejected() {
        assertEquals(AcceptResult.DISABLED, service.accept(playerId, "off"));
    }

    @Test
    void unknownContractRejected() {
        assertEquals(AcceptResult.NOT_FOUND, service.accept(playerId, "nope"));
    }

    @Test
    void progressCompletesAndClaimRewardsExactlyOnce() {
        service.accept(playerId, "a");
        service.progress(playerId, ObjectiveType.BREAK_BLOCK, "STONE", 5);
        assertEquals(Status.COMPLETE, progress("a").status());
        assertEquals(5, progress("a").progress());
        assertTrue(notifications.contains(Messages.OBJECTIVE_COMPLETE));

        assertEquals(ClaimResult.OK, service.claim(playerId, "a"));
        await(() -> progress("a").status() == Status.CLAIMED);
        assertEquals(1, rewards.applied.size());
        assertEquals(100, rewards.applied.get(0).exp());

        assertEquals(ClaimResult.NOT_COMPLETE, service.claim(playerId, "a"));
        assertEquals(1, rewards.applied.size(), "reward must not be delivered twice");
        assertTrue(notifications.contains(Messages.CLAIMED));
    }

    @Test
    void claimBeforeCompleteRejected() {
        service.accept(playerId, "a");
        assertEquals(ClaimResult.NOT_COMPLETE, service.claim(playerId, "a"));
        assertTrue(rewards.applied.isEmpty());
    }

    @Test
    void wrongTargetDoesNotProgress() {
        service.accept(playerId, "a");
        service.progress(playerId, ObjectiveType.BREAK_BLOCK, "OAK_LOG", 2);
        assertEquals(0, progress("a").progress());
    }

    @Test
    void cooldownBlocksRepeatableReaccept() {
        service.accept(playerId, "b");
        service.progress(playerId, ObjectiveType.BREAK_BLOCK, "OAK_LOG", 3);
        assertEquals(ClaimResult.OK, service.claim(playerId, "b"));
        await(() -> progress("b").status() == Status.CLAIMED);
        assertEquals(AcceptResult.COOLDOWN, service.accept(playerId, "b"));
        assertTrue(progress("b").cooldownUntil() > System.currentTimeMillis());
    }

    @Test
    void nonRepeatableCannotBeReaccepted() {
        service.accept(playerId, "a");
        service.progress(playerId, ObjectiveType.BREAK_BLOCK, "STONE", 5);
        service.claim(playerId, "a");
        await(() -> progress("a").status() == Status.CLAIMED);
        assertEquals(AcceptResult.NOT_REPEATABLE, service.accept(playerId, "a"));
    }

    @Test
    void abandonFreesSlotAndAllowsReaccept() {
        service.accept(playerId, "a");
        service.accept(playerId, "b");
        assertEquals(AcceptResult.LIMIT, service.accept(playerId, "c"));
        assertTrue(service.abandon(playerId, "a"));
        assertEquals(Status.ABANDONED, progress("a").status());
        assertEquals(AcceptResult.OK, service.accept(playerId, "c"));
    }

    @Test
    void abandonUnknownContractFails() {
        assertFalse(service.abandon(playerId, "nope"));
    }

    @Test
    void persistenceAcrossRestart() throws Exception {
        service.accept(playerId, "a");
        service.progress(playerId, ObjectiveType.BREAK_BLOCK, "STONE", 3);
        service.flushDirty().get();
        store.close();

        PlayerDataStore reopened = new PlayerDataStore(database, LOG);
        try {
            ContractProgress restored = reopened.load(playerId).get().contracts().get("a");
            assertEquals(Status.ACTIVE, restored.status());
            assertEquals(3, restored.progress());
        } finally {
            reopened.close();
        }
    }

    @Test
    void adminCompleteAllowsClaim() {
        assertTrue(service.adminComplete(playerId, "a"));
        assertEquals(Status.COMPLETE, progress("a").status());
        assertEquals(5, progress("a").progress());
        assertEquals(ClaimResult.OK, service.claim(playerId, "a"));
        await(() -> progress("a").status() == Status.CLAIMED);
        assertEquals(1, rewards.applied.size());
    }

    @Test
    void adminAssignForcesEvenDisabledContract() {
        assertTrue(service.adminAssign(playerId, "off"));
        assertEquals(Status.ACTIVE, progress("off").status());
    }

    @Test
    void adminResetRemovesSingleContract() {
        service.accept(playerId, "a");
        assertTrue(service.adminReset(playerId, "a"));
        assertTrue(service.contracts(playerId).isEmpty());
    }

    @Test
    void adminResetClearsEverything() {
        service.accept(playerId, "a");
        service.accept(playerId, "b");
        assertTrue(service.adminReset(playerId, null));
        assertTrue(service.contracts(playerId).isEmpty());
    }

    @Test
    void quitFlushesStateAndUnloadsMemory() throws Exception {
        service.accept(playerId, "a");
        service.unloadPlayer(playerId).get();
        assertTrue(service.contracts(playerId).isEmpty());
        store.close();

        PlayerDataStore reopened = new PlayerDataStore(database, LOG);
        try {
            assertEquals(Status.ACTIVE, reopened.load(playerId).get().contracts().get("a").status());
        } finally {
            reopened.close();
        }
    }

    private static final class RecordingRewards implements RewardHandler {

        private final List<Reward> applied = new ArrayList<>();

        @Override
        public void apply(UUID player, Reward reward) {
            applied.add(reward);
        }
    }
}
