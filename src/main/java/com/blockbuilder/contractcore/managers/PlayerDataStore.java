package com.blockbuilder.contractcore.managers;

import com.blockbuilder.contractcore.models.ContractProgress;
import com.blockbuilder.contractcore.models.PlayerData;
import com.blockbuilder.contractcore.models.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQLite-backed player data store. All I/O runs on a single dedicated thread;
 * nothing here may touch the server main thread. Schema changes happen in
 * {@link #migrate()} based on the PRAGMA user_version.
 */
public final class PlayerDataStore implements AutoCloseable {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS progress (
                player_uuid     TEXT    NOT NULL,
                contract_id     TEXT    NOT NULL,
                progress        INTEGER NOT NULL DEFAULT 0,
                status          TEXT    NOT NULL DEFAULT 'ACTIVE',
                accepted_at     INTEGER NOT NULL,
                completed_at    INTEGER NOT NULL DEFAULT 0,
                claimed_at      INTEGER NOT NULL DEFAULT 0,
                cooldown_until  INTEGER NOT NULL DEFAULT 0,
                completed_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player_uuid, contract_id)
            )""";

    private static final String INSERT_SQL = """
            INSERT INTO progress
                (player_uuid, contract_id, progress, status, accepted_at, completed_at, claimed_at, cooldown_until, completed_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String CLAIM_SQL = """
            UPDATE progress
            SET status = 'CLAIMED', claimed_at = ?, cooldown_until = ?, completed_count = completed_count + 1, progress = ?
            WHERE player_uuid = ? AND contract_id = ? AND status IN ('COMPLETE', 'ACTIVE')""";

    private final Logger logger;
    private final Connection connection;
    private final ExecutorService executor;
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    public PlayerDataStore(Path databaseFile, Logger logger) throws IOException, SQLException {
        this.logger = logger;
        Files.createDirectories(databaseFile.getParent());
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        connection.setAutoCommit(false);
        migrate();
        this.executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ContractCore-DB").daemon().factory());
    }

    public CompletableFuture<PlayerData> load(UUID uuid) {
        return submit(() -> {
            Map<String, ContractProgress> contracts = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT contract_id, progress, status, accepted_at, completed_at, claimed_at, cooldown_until, completed_count"
                            + " FROM progress WHERE player_uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        contracts.put(rs.getString(1), new ContractProgress(
                                rs.getInt(2),
                                Status.valueOf(rs.getString(3)),
                                rs.getLong(4),
                                rs.getLong(5),
                                rs.getLong(6),
                                rs.getLong(7),
                                rs.getInt(8)));
                    }
                }
            }
            return new PlayerData(uuid, contracts, true);
        });
    }

    public CompletableFuture<Optional<ContractProgress>> loadOne(UUID uuid, String contractId) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT progress, status, accepted_at, completed_at, claimed_at, cooldown_until, completed_count"
                            + " FROM progress WHERE player_uuid = ? AND contract_id = ?")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, contractId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ContractProgress(
                            rs.getInt(1),
                            Status.valueOf(rs.getString(2)),
                            rs.getLong(3),
                            rs.getLong(4),
                            rs.getLong(5),
                            rs.getLong(6),
                            rs.getInt(7)));
                }
            }
        });
    }

    public CompletableFuture<Void> save(PlayerData data) {
        return submit(() -> {
            try {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM progress WHERE player_uuid = ?")) {
                    delete.setString(1, data.uuid().toString());
                    delete.executeUpdate();
                }
                if (!data.contracts().isEmpty()) {
                    try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
                        for (Map.Entry<String, ContractProgress> entry : data.contracts().entrySet()) {
                            ContractProgress progress = entry.getValue();
                            insert.setString(1, data.uuid().toString());
                            insert.setString(2, entry.getKey());
                            insert.setInt(3, progress.progress());
                            insert.setString(4, progress.status().name());
                            insert.setLong(5, progress.acceptedAt());
                            insert.setLong(6, progress.completedAt());
                            insert.setLong(7, progress.claimedAt());
                            insert.setLong(8, progress.cooldownUntil());
                            insert.setInt(9, progress.completedCount());
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
            return null;
        });
    }

    /**
     * Atomically marks a completed contract as claimed. Returns true only for
     * the caller that actually performed the transition; a second attempt sees
     * status CLAIMED and returns false. This is the guard against double claims.
     */
    public CompletableFuture<Boolean> claimTransition(UUID uuid, String contractId, int progress, long now, long cooldownUntil) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(CLAIM_SQL)) {
                statement.setLong(1, now);
                statement.setLong(2, cooldownUntil);
                statement.setInt(3, progress);
                statement.setString(4, uuid.toString());
                statement.setString(5, contractId);
                int rows = statement.executeUpdate();
                connection.commit();
                return rows == 1;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        });
    }

    public Set<UUID> dirty() {
        return dirty;
    }

    private <T> CompletableFuture<T> submit(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Storage failure", ex);
                throw new CompletionException(ex);
            }
        }, executor);
    }

    private void migrate() throws SQLException {
        int version = userVersion();
        if (version == 0) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE);
                statement.execute("PRAGMA user_version=1");
            }
            connection.commit();
            logger.info("Storage schema created (version 1)");
        }
    }

    private int userVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private volatile boolean closed;

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warning("Storage thread did not finish in time; forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, "Failed to close the database", ex);
        }
    }
}
