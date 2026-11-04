package com.convenientclipboard.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** Serializes quota, migration and deletion changes, including against scheduled maintenance. */
@Component
public class StorageCoordinator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public StorageCoordinator(JdbcTemplate jdbc, TransactionTemplate transactions) { this.jdbc=jdbc; this.transactions=transactions; }
    public <T> T locked(Supplier<T> operation) {
        return transactions.execute(status -> {
            jdbc.execute("select pg_advisory_xact_lock(787459131)");
            return operation.get();
        });
    }
}
