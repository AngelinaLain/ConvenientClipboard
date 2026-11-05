package com.convenientclipboard.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;

/** Serializes quota, migration and deletion changes, including against scheduled maintenance. */
@Component
public class StorageCoordinator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcTemplate leaseJdbc;
    private final TransactionTemplate leaseTransactions;
    private final HikariDataSource leasePool;
    public StorageCoordinator(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc=jdbc;this.transactions=transactions;
        DataSource source=jdbc.getDataSource();
        if(source instanceof HikariDataSource primary) {
            // Waiting task locks can occupy every main-pool connection. Progress must not borrow
            // a second connection from that same pool while its first connection remains checked out.
            var configuration=new HikariConfig();
            primary.copyStateTo(configuration);
            configuration.setPoolName((primary.getPoolName()==null?"clipboard":primary.getPoolName())+"-upload-leases");
            configuration.setMaximumPoolSize(2);configuration.setMinimumIdle(0);
            configuration.setConnectionTimeout(5000);configuration.setRegisterMbeans(false);
            leasePool=new HikariDataSource(configuration);
            leaseJdbc=new JdbcTemplate(leasePool);
            leaseTransactions=new TransactionTemplate(new DataSourceTransactionManager(leasePool));
        } else {
            leasePool=null;leaseJdbc=source==null?jdbc:new JdbcTemplate(source);
            leaseTransactions=new TransactionTemplate(transactions.getTransactionManager());
        }
        leaseJdbc.setQueryTimeout(5);
        leaseTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public <T> T locked(Supplier<T> operation) {
        return transactions.execute(status -> {
            jdbc.execute("select pg_advisory_xact_lock(787459131)");
            return operation.get();
        });
    }
    /** Always acquire the task lock before the global capacity lock when both are needed. */
    public <T> T uploadLocked(UUID id, Supplier<T> operation) {
        return transactions.execute(status -> {
            // The two-int advisory namespace cannot collide with the existing bigint capacity lock.
            jdbc.queryForObject("select pg_advisory_xact_lock(?,?)", Object.class, 787459133, id.hashCode());
            return operation.get();
        });
    }
    /** Call under the capacity lock when admitting files or changing capacity. */
    public long reservedUploadBytes(UUID excluding) {
        return jdbc.queryForObject("select coalesce(sum(size_bytes),0) from upload_tasks where state='UPLOADING' "
                + "and expires_at>clock_timestamp() and (?::uuid is null or id<>?::uuid)", Long.class, excluding, excluding);
    }
    /** A long-running task transaction must publish its progress deadline to other quota checks. */
    public boolean renewUpload(UUID id,Duration lifetime) {
        return Boolean.TRUE.equals(leaseTransactions.execute(status -> {
            // Serialize deadline extension with quota admission so an expiry cannot be observed
            // between this UPDATE and its commit, then reintroduced after another file is admitted.
            leaseJdbc.execute("select pg_advisory_xact_lock(787459131)");
            return leaseJdbc.update("update upload_tasks set expires_at=? where id=? and state='UPLOADING' and expires_at>clock_timestamp()",
                    Timestamp.from(Instant.now().plus(lifetime)),id)==1;
        }));
    }
    @PreDestroy public void close() { if(leasePool!=null) leasePool.close(); }
}
