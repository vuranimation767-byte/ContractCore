package com.blockbuilder.contractcore.managers;

import com.blockbuilder.contractcore.models.ContractProgress;
import com.blockbuilder.contractcore.models.PlayerData;
import com.blockbuilder.contractcore.models.Status;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class PlayerDataStoreTest {

    private static final Logger LOG = Logger.getLogger("contractcore-test");

    private static ContractProgress row(int progress, Status status) {
        return new ContractProgress(progress, status, 1000L, 0L, 0L, 0L, 0);
    }

    private static Map<String, ContractProgress> sampleRows() {
        Map<String, ContractProgress> contracts = new HashMap<>();
        contracts.put("a", row(3, Status.ACTIVE));
        contracts.put("b", new ContractProgress(9, Status.COMPLETE, 1L, 2L, 3L, 4L, 2));
        return contracts;
    }

    @Test
    void saveLoadRoundtrip(@TempDir Path dir) throws Exception {
        PlayerDataStore store = new PlayerDataStore(dir.resolve("test.db"), LOG);
        try {
            UUID uuid = UUID.randomUUID();
            store.save(new PlayerData(uuid, sampleRows(), true)).get();
            PlayerData loaded = store.load(uuid).get();
            assertTrue(loaded.loaded());
            assertEquals(sampleRows(), loaded.contracts());
        } finally {
            store.close();
        }
    }

    @Test
    void loadMissingPlayerReturnsEmptyData(@TempDir Path dir) throws Exception {
        PlayerDataStore store = new PlayerDataStore(dir.resolve("test.db"), LOG);
        try {
            PlayerData loaded = store.load(UUID.randomUUID()).get();
            assertTrue(loaded.loaded());
            assertTrue(loaded.contracts().isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    void claimTransitionMarksClaimedExactlyOnce(@TempDir Path dir) throws Exception {
        PlayerDataStore store = new PlayerDataStore(dir.resolve("test.db"), LOG);
        try {
            UUID uuid = UUID.randomUUID();
            store.save(new PlayerData(uuid, Map.of("a", row(5, Status.COMPLETE)), true)).get();
            assertTrue(store.claimTransition(uuid, "a", 5, 2000L, 0L).get());
            assertFalse(store.claimTransition(uuid, "a", 5, 3000L, 0L).get());
            Optional<ContractProgress> loaded = store.loadOne(uuid, "a").get();
            assertTrue(loaded.isPresent());
            assertEquals(Status.CLAIMED, loaded.get().status());
            assertEquals(2000L, loaded.get().claimedAt());
            assertEquals(1, loaded.get().completedCount());
        } finally {
            store.close();
        }
    }

    @Test
    void claimTransitionOnMissingRowReturnsFalse(@TempDir Path dir) throws Exception {
        PlayerDataStore store = new PlayerDataStore(dir.resolve("test.db"), LOG);
        try {
            assertFalse(store.claimTransition(UUID.randomUUID(), "a", 5, 2000L, 0L).get());
        } finally {
            store.close();
        }
    }

    @Test
    void reopenSameFilePreservesData(@TempDir Path dir) throws Exception {
        UUID uuid = UUID.randomUUID();
        Path file = dir.resolve("test.db");
        PlayerDataStore first = new PlayerDataStore(file, LOG);
        first.save(new PlayerData(uuid, sampleRows(), true)).get();
        first.close();
        PlayerDataStore second = new PlayerDataStore(file, LOG);
        try {
            assertEquals(sampleRows(), second.load(uuid).get().contracts());
        } finally {
            second.close();
        }
    }

    @Test
    void freshDatabaseGetsSchemaVersionOne(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("test.db");
        PlayerDataStore store = new PlayerDataStore(file, LOG);
        store.close();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void saveAfterClaimPersistsClaimedState(@TempDir Path dir) throws Exception {
        PlayerDataStore store = new PlayerDataStore(dir.resolve("test.db"), LOG);
        try {
            UUID uuid = UUID.randomUUID();
            store.save(new PlayerData(uuid, Map.of("a", row(5, Status.COMPLETE)), true)).get();
            assertTrue(store.claimTransition(uuid, "a", 5, 2000L, 0L).get());
            PlayerData reloaded = store.load(uuid).get();
            assertEquals(Status.CLAIMED, reloaded.contracts().get("a").status());
        } finally {
            store.close();
        }
    }
}
