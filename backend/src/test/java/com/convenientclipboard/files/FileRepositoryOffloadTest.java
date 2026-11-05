package com.convenientclipboard.files;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL ordering semantics, isolated in connection-local temporary tables. */
@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL",matches=".+")
class FileRepositoryOffloadTest {
    SingleConnectionDataSource dataSource;
    FileRepository files;
    Instant now;

    @BeforeEach void setUp() throws Exception {
        var connection=DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("TEST_POSTGRES_USERNAME","clipboard_test"),
                System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD","clipboard_test"));
        dataSource=new SingleConnectionDataSource(connection,true);
        var jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("""
                create temporary table stored_files (
                    id uuid primary key, owner_id uuid, original_name varchar(255), content_type varchar(255),
                    size_bytes bigint, object_key varchar(255), share_token varchar(255), permanent boolean,
                    expires_at timestamptz, location varchar(16), provider_id uuid, created_at timestamptz)
                """);
        now=jdbc.queryForObject("select now()",Timestamp.class).toInstant();
        files=new FileRepository(jdbc);
    }
    @AfterEach void close() { if(dataSource!=null) dataSource.destroy(); }

    UUID id(int value) { return new UUID(0,value); }
    void insert(int value,boolean permanent,Instant expiry,Instant created,String location) {
        UUID id=id(value);
        files.insert(new StoredFile(id,permanent?new UUID(1,1):null,"file-"+value,"text/plain",1,
                id.toString(),"share-"+value,permanent,expiry,location,location.equals("LOCAL")?null:new UUID(2,2),created));
    }
    List<UUID> candidates() { return files.localCandidates().stream().map(StoredFile::id).toList(); }

    @Test void permanentFilesComeFirstAndOlderPermanentFilesWinTies() {
        insert(1,false,now.plusSeconds(365L*86400),now.minusSeconds(100000),"LOCAL");
        insert(30,true,null,now.minusSeconds(60),"LOCAL");
        insert(20,true,null,now.minusSeconds(120),"LOCAL");
        insert(10,true,null,now.minusSeconds(120),"LOCAL");
        assertThat(candidates()).containsExactly(id(10),id(20),id(30),id(1));
    }
    @Test void temporaryFilesUseLongestRemainingLifetimeBeforeUploadAge() {
        insert(1,false,now.plusSeconds(600),now.minusSeconds(60L*86400),"LOCAL");
        insert(2,false,now.plusSeconds(30L*86400),now.minusSeconds(30),"LOCAL");
        insert(3,false,now.plusSeconds(86400),now.minusSeconds(86400),"LOCAL");
        assertThat(candidates()).containsExactly(id(2),id(3),id(1));
    }
    @Test void matchingTemporaryDeadlinesUseCreationTimeThenIdForDeterministicOrder() {
        Instant expiry=now.plusSeconds(86400);
        insert(20,false,expiry,now.minusSeconds(120),"LOCAL");
        insert(1,false,expiry,now.minusSeconds(60),"LOCAL");
        insert(10,false,expiry,now.minusSeconds(120),"LOCAL");
        assertThat(candidates()).containsExactly(id(10),id(20),id(1));
    }
    @Test void expiredAndAlreadyRemoteFilesAreNeverOffloadCandidates() {
        insert(1,false,now.plusSeconds(600),now.minusSeconds(120),"LOCAL");
        insert(2,true,null,now.minusSeconds(60),"LOCAL");
        insert(3,false,now.minusSeconds(1),now.minusSeconds(1000),"LOCAL");
        insert(4,true,null,now.minusSeconds(1000),"S3");
        insert(5,false,now.plusSeconds(365L*86400),now.minusSeconds(1000),"NFS");
        insert(6,true,null,now.minusSeconds(1000),"SMB");
        assertThat(candidates()).containsExactly(id(2),id(1));
    }
    @Test void theHundredFileBatchLimitIsAppliedAfterPermanentAndExpiryPriorities() {
        for(int index=1;index<=110;index++)
            insert(index,false,now.plusSeconds(10000+index),now.minusSeconds(10000-index),"LOCAL");
        // Both permanent files are newer than every temporary file and must still enter this batch.
        insert(1000,true,null,now,"LOCAL");
        insert(999,true,null,now.minusSeconds(1),"LOCAL");
        List<UUID> expected=new ArrayList<>(List.of(id(999),id(1000)));
        IntStream.iterate(110,index->index-1).limit(98).forEach(index->expected.add(id(index)));
        assertThat(candidates()).hasSize(100).containsExactlyElementsOf(expected);
    }
}
