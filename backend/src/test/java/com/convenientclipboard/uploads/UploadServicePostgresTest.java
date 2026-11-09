package com.convenientclipboard.uploads;

import com.convenientclipboard.auth.AppPrincipal;
import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import com.convenientclipboard.files.*;
import com.convenientclipboard.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Explicit PostgreSQL opt-in. A private random schema and temporary disk directory isolate every run. */
@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadServicePostgresTest {
    static final String TOKEN="a".repeat(64);
    @TempDir Path directory;
    JdbcTemplate admin,jdbc;
    DriverManagerDataSource dataSource;
    String schema;
    AppProperties properties;
    UploadRepository tasks;
    UploadStorage parts;
    FileRepository files;
    FileService fileService;
    LocalStorage local;
    SettingsService settings;
    StorageCoordinator coordinator;
    UploadService service;
    ApplicationEventPublisher events;

    @BeforeAll void database() {
        String url=System.getenv("TEST_POSTGRES_URL");
        String username=System.getenv().getOrDefault("TEST_POSTGRES_USERNAME","clipboard_test");
        String password=System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD","clipboard_test");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,username,password));
        schema="upload_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("create schema "+schema);
        dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,username,password);
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc=new JdbcTemplate(dataSource);
    }
    @AfterAll void removeSchema() { if(admin!=null && schema!=null) admin.execute("drop schema "+schema+" cascade"); }
    @BeforeEach void setUp() throws Exception {
        jdbc.execute("truncate stored_files,upload_tasks,app_users cascade");
        jdbc.update("update app_settings set local_capacity_bytes=?,offload_threshold=50,max_upload_bytes=?,allow_anonymous_uploads=true,registration_enabled=true where id=1",64L*1024*1024,32L*1024*1024);
        properties=new AppProperties("https://files.test",directory,32L*1024*1024,"j".repeat(32),"admin","password123","k".repeat(32));
        local=new LocalStorage(properties);
        // @TempDir has class lifecycle here; reset disk state between tests.
        for(Path path:local.objects()) Files.delete(path);
        Path uploadRoot=directory.resolve("uploads");
        if(Files.exists(uploadRoot)) try(var paths=Files.walk(uploadRoot)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        coordinator=new StorageCoordinator(jdbc,new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        settings=new SettingsService(jdbc,coordinator,local,properties);
        files=new FileRepository(jdbc);
        events=mock(ApplicationEventPublisher.class);
        fileService=new FileService(files,local,mock(StorageAccess.class),settings,coordinator,properties,events);
        tasks=spy(new UploadRepository(jdbc,new ObjectMapper().findAndRegisterModules()));
        restart();
    }
    void restart() throws Exception {
        parts=new UploadStorage(properties);
        service=new UploadService(tasks,parts,files,fileService,local,settings,coordinator,events);
    }
    UploadRequest request(UUID id,byte[] bytes,boolean permanent) {
        List<String> hashes=new ArrayList<>();
        for(int offset=0;offset<bytes.length;offset+=UploadTask.CHUNK_SIZE)
            hashes.add(UploadStorage.hash(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+UploadTask.CHUNK_SIZE))));
        return new UploadRequest(id,"report.bin","application/octet-stream",bytes.length,permanent,hashes);
    }
    UploadRequest request(byte[] bytes) { return request(UUID.randomUUID(),bytes,false); }
    byte[] bytes(int size) { byte[] data=new byte[size];for(int index=0;index<size;index++) data[index]=(byte)(index*31);return data; }
    UploadStatus chunk(UploadRequest request,int index,byte[] body) {
        return service.chunk(request.id(),index,TOKEN,null,new ByteArrayInputStream(body),body.length);
    }
    void error(HttpStatus status,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,ex -> assertThat(ex.status()).isEqualTo(status));
    }
    void upload(UploadRequest request,byte[] data) {
        service.create(request,TOKEN,null);
        for(int index=0;index<request.chunkHashes().size();index++)
            chunk(request,index,Arrays.copyOfRange(data,index*UploadTask.CHUNK_SIZE,Math.min(data.length,(index+1)*UploadTask.CHUNK_SIZE)));
    }

    @Test void persistedAccessSwitchesBlockGuestTasksAndOlderSettingsRequestsPreserveThem() {
        byte[] data=bytes(12);
        UploadRequest request=request(data);
        service.create(request,TOKEN,null);
        settings.save(new StorageSettings(64L*1048576,50,32L*1048576,false,false));
        SettingsService restarted=new SettingsService(jdbc,coordinator,local,properties);
        assertThat(restarted.get().allowAnonymousUploads()).isFalse();
        assertThat(restarted.get().registrationEnabled()).isFalse();
        StorageSettings legacySave=restarted.save(new StorageSettings(64L*1048576,60,32L*1048576));
        assertThat(legacySave.allowAnonymousUploads()).isFalse();
        assertThat(legacySave.registrationEnabled()).isFalse();
        error(HttpStatus.FORBIDDEN,() -> chunk(request,0,data));
        error(HttpStatus.FORBIDDEN,() -> service.complete(request.id(),TOKEN,null));
        error(HttpStatus.FORBIDDEN,() -> service.create(request,TOKEN,null));
        assertThat(service.get(request.id(),TOKEN,null).state()).isEqualTo("UPLOADING");
        assertThat(coordinator.reservedUploadBytes(null)).isEqualTo(data.length);
        service.cancel(request.id(),TOKEN,null);
        assertThat(coordinator.reservedUploadBytes(null)).isZero();
        restarted.save(new StorageSettings(64L*1048576,50,32L*1048576,true,true));
        UploadRequest reopened=request(data);
        upload(reopened,data);
        assertThat(service.complete(reopened.id(),TOKEN,null).state()).isEqualTo("COMPLETED");
    }

    @Test void diskReceiptRecoveryRestartAndCompletionAreIdempotent() throws Exception {
        byte[] data=bytes(UploadTask.CHUNK_SIZE+3);
        UploadRequest request=request(data);
        assertThat(service.create(request,TOKEN,null).receivedChunks()).isEmpty();
        chunk(request,0,Arrays.copyOfRange(data,0,UploadTask.CHUNK_SIZE));
        // Model a crash after atomic rename but before receipt transaction commit.
        jdbc.update("delete from upload_chunks where task_id=?",request.id());
        restart();
        UploadStatus recovered=service.get(request.id(),TOKEN,null);
        assertThat(recovered.receivedChunks()).containsExactly(0);
        assertThat(recovered.uploadedBytes()).isEqualTo(UploadTask.CHUNK_SIZE);
        chunk(request,1,Arrays.copyOfRange(data,UploadTask.CHUNK_SIZE,data.length));
        UploadStatus done=service.complete(request.id(),TOKEN,null);
        assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(done.file().id()).isEqualTo(request.id());
        assertThat(Files.readAllBytes(local.path(request.id().toString()))).isEqualTo(data);
        assertThat(service.complete(request.id(),TOKEN,null).file().shareUrl()).isEqualTo(done.file().shareUrl());
        assertThat(service.create(request,TOKEN,null).file().shareUrl()).isEqualTo(done.file().shareUrl());
        assertThat(jdbc.queryForObject("select count(*) from stored_files",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select token_hash from upload_tasks where id=?",String.class,request.id())).isNotEqualTo(TOKEN);
        assertThat(Files.exists(parts.directory(request.id()))).isFalse();
        // Deleting a file must not let a retried complete/create recreate it.
        files.delete(request.id());local.delete(request.id().toString());
        assertThat(service.complete(request.id(),TOKEN,null).file().shareUrl()).isEqualTo(done.file().shareUrl());
        assertThat(files.total()).isZero();
    }
    @Test void chunkLengthHashAndDuplicateBodiesAreCheckedWithBoundedReads() {
        byte[] data=bytes(17);UploadRequest request=request(data);service.create(request,TOKEN,null);
        error(HttpStatus.BAD_REQUEST,() -> service.chunk(request.id(),0,TOKEN,null,new ByteArrayInputStream(new byte[16]),-1));
        var overlong=new ByteArrayInputStream(new byte[1000]);
        error(HttpStatus.PAYLOAD_TOO_LARGE,() -> service.chunk(request.id(),0,TOKEN,null,overlong,-1));
        assertThat(overlong.available()).isEqualTo(982); // expected + one byte, never the rest of the stream.
        error(HttpStatus.UNPROCESSABLE_ENTITY,() -> chunk(request,0,new byte[17]));
        assertThat(service.get(request.id(),TOKEN,null).receivedChunks()).isEmpty();
        chunk(request,0,data);chunk(request,0,data);
        error(HttpStatus.UNPROCESSABLE_ENTITY,() -> chunk(request,0,new byte[17]));
        assertThat(service.get(request.id(),TOKEN,null).receivedChunks()).containsExactly(0);
        assertThat(service.complete(request.id(),TOKEN,null).uploadedBytes()).isEqualTo(17);
    }
    @Test void concurrentCompletesCommitOneFileAndShareLink() throws Exception {
        byte[] data=bytes(4096);UploadRequest request=request(data);upload(request,data);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<UploadStatus> complete=() -> { start.await();return service.complete(request.id(),TOKEN,null); };
            Future<UploadStatus> first=pool.submit(complete),second=pool.submit(complete);start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS).file().shareUrl()).isEqualTo(second.get(10,TimeUnit.SECONDS).file().shareUrl());
        }
        assertThat(files.total()).isEqualTo(1);
    }
    @Test void failedCompletionRollsBackFileAndTaskThenRecoversOrphanObject() throws Exception {
        byte[] data=bytes(4096);UploadRequest request=request(data);upload(request,data);
        doThrow(new IllegalStateException("simulated transaction failure")).when(tasks).complete(eq(request.id()),any(),any());
        assertThatThrownBy(() -> service.complete(request.id(),TOKEN,null)).isInstanceOf(IllegalStateException.class);
        assertThat(files.total()).isZero();
        assertThat(service.get(request.id(),TOKEN,null).state()).isEqualTo("UPLOADING");
        assertThat(Files.exists(parts.part(request.id(),0))).isTrue();
        doCallRealMethod().when(tasks).complete(eq(request.id()),any(),any());
        assertThat(service.complete(request.id(),TOKEN,null).file().id()).isEqualTo(request.id());
        assertThat(files.total()).isEqualTo(1);
    }
    @Test void reservationsBlockLegacyAndOtherTasksButNotTheirOwnCompletion() {
        long mib=1048576;jdbc.update("update app_settings set local_capacity_bytes=? where id=1",2*mib);
        byte[] data=bytes((int)mib+1);UploadRequest request=request(data);upload(request,data);
        error(HttpStatus.INSUFFICIENT_STORAGE,() -> service.create(request(new byte[(int)mib]),TOKEN,null));
        error(HttpStatus.INSUFFICIENT_STORAGE,() -> fileService.upload(new MockMultipartFile("file",new byte[(int)mib]),false,null));
        error(HttpStatus.CONFLICT,() -> settings.save(new StorageSettings(mib,50,32*mib)));
        assertThat(service.complete(request.id(),TOKEN,null).state()).isEqualTo("COMPLETED");
        assertThat(coordinator.reservedUploadBytes(null)).isZero();
    }
    @Test void expiryAndUnknownCancellationKeepTombstonesAndReleaseBudget() throws Exception {
        byte[] data=bytes(1024);UploadRequest request=request(data);upload(request,data);
        jdbc.update("update upload_tasks set expires_at=now()-interval '1 second' where id=?",request.id());
        // Quota is released immediately by its deadline, before the periodic physical cleanup.
        assertThat(coordinator.reservedUploadBytes(null)).isZero();
        assertThat(Files.exists(parts.part(request.id(),0))).isTrue();
        error(HttpStatus.GONE,() -> service.get(request.id(),TOKEN,null));
        service.maintain();
        assertThat(tasks.find(request.id()).state()).isEqualTo("EXPIRED");
        assertThat(Files.exists(parts.directory(request.id()))).isFalse();
        assertThat(coordinator.reservedUploadBytes(null)).isZero();
        error(HttpStatus.GONE,() -> service.create(request,TOKEN,null));
        UploadRequest late=request(data);service.cancel(late.id(),TOKEN,null);
        error(HttpStatus.GONE,() -> service.create(late,TOKEN,null));
        error(HttpStatus.NOT_FOUND,() -> service.get(late.id(),"b".repeat(64),null));
    }
    @Test void successfulChunksSlideTheTenMinuteDeadlineButProgressReadsDoNot() {
        byte[] data=bytes(1024);UploadRequest request=request(data);
        Instant before=Instant.now();
        UploadStatus created=service.create(request,TOKEN,null);
        assertThat(created.expiresAt()).isBetween(before.plusSeconds(600).minusMillis(1),Instant.now().plusSeconds(600).plusMillis(1));
        Instant oldDeadline=Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update upload_tasks set expires_at=? where id=?",Timestamp.from(oldDeadline),request.id());
        assertThat(service.get(request.id(),TOKEN,null).expiresAt()).isEqualTo(oldDeadline);
        assertThat(service.get(request.id(),TOKEN,null).expiresAt()).isEqualTo(oldDeadline);
        before=Instant.now();
        UploadStatus received=chunk(request,0,data);
        assertThat(received.expiresAt()).isBetween(before.plusSeconds(600).minusMillis(1),Instant.now().plusSeconds(600).plusMillis(1));
        assertThat(Duration.between(oldDeadline,received.expiresAt()).toSeconds()).isGreaterThanOrEqualTo(479);
        assertThat(service.get(request.id(),TOKEN,null).expiresAt()).isEqualTo(received.expiresAt());
        UploadStatus completed=service.complete(request.id(),TOKEN,null);
        assertThat(Duration.between(Instant.now(),completed.expiresAt()).toDays()).isEqualTo(6);
    }
    @Test void activeReceiveAndMergePublishRenewalAndSurviveTheirOriginalDeadline() throws Exception {
        byte[] data=bytes(1024);UploadRequest request=request(data);service.create(request,TOKEN,null);
        Instant original=Instant.now().plusSeconds(1);
        jdbc.update("update upload_tasks set expires_at=? where id=?",Timestamp.from(original),request.id());
        InputStream delayed=new ByteArrayInputStream(data) {
            boolean first=true;
            @Override public synchronized int read(byte[] bytes,int offset,int length) {
                if(first) {
                    first=false;
                    // An unrelated connection sees the renewal before the long request transaction commits.
                    Instant visible=admin.queryForObject("select expires_at from "+schema+".upload_tasks where id=?",Timestamp.class,request.id()).toInstant();
                    assertThat(visible).isAfter(original.plusSeconds(590));
                    try { Thread.sleep(1100); } catch(InterruptedException ex) { throw new AssertionError(ex); }
                }
                return super.read(bytes,offset,length);
            }
        };
        assertThat(service.chunk(request.id(),0,TOKEN,null,delayed,data.length).receivedChunks()).containsExactly(0);
        var slowParts=spy(parts);
        doAnswer(invocation -> { Thread.sleep(1100);return invocation.callRealMethod(); }).when(slowParts).merge(any(),any());
        service=new UploadService(tasks,slowParts,files,fileService,local,settings,coordinator,events);
        jdbc.update("update upload_tasks set expires_at=? where id=?",Timestamp.from(Instant.now().plusSeconds(1)),request.id());
        assertThat(service.complete(request.id(),TOKEN,null).state()).isEqualTo("COMPLETED");
    }
    @Test void anIndependentProgressRenewalCannotReviveAnExpiredTask() {
        UploadRequest request=request(bytes(1024));service.create(request,TOKEN,null);
        Instant expired=Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update upload_tasks set expires_at=? where id=?",Timestamp.from(expired),request.id());
        assertThat(coordinator.renewUpload(request.id(),Duration.ofMinutes(10))).isFalse();
        assertThat(tasks.find(request.id()).expiresAt()).isEqualTo(expired);
        assertThat(coordinator.reservedUploadBytes(null)).isZero();
    }
    @Test void progressRenewalsDoNotBorrowFromAnExhaustedMainPool() throws Exception {
        UploadRequest first=request(bytes(10)),second=request(bytes(11));
        service.create(first,TOKEN,null);service.create(second,TOKEN,null);
        var configuration=new HikariConfig();
        configuration.setJdbcUrl(dataSource.getUrl());configuration.setUsername(dataSource.getUsername());
        configuration.setPassword(dataSource.getPassword());configuration.setMaximumPoolSize(2);configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(2000);configuration.setPoolName("upload-main-pool-regression");
        try(var mainPool=new HikariDataSource(configuration);var workers=Executors.newFixedThreadPool(2)) {
            var guarded=new StorageCoordinator(new JdbcTemplate(mainPool),new TransactionTemplate(new DataSourceTransactionManager(mainPool)));
            try {
                CountDownLatch occupied=new CountDownLatch(2),renew=new CountDownLatch(1);
                java.util.function.Function<UUID,Boolean> operation=id -> guarded.uploadLocked(id,() -> {
                    occupied.countDown();
                    try { if(!renew.await(5,TimeUnit.SECONDS)) throw new AssertionError("Renewal was not released"); }
                    catch(InterruptedException ex) { throw new AssertionError(ex); }
                    return guarded.renewUpload(id,Duration.ofMinutes(10));
                });
                Future<Boolean> one=workers.submit(() -> operation.apply(first.id()));
                Future<Boolean> two=workers.submit(() -> operation.apply(second.id()));
                assertThat(occupied.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(mainPool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
                renew.countDown();
                assertThat(one.get(5,TimeUnit.SECONDS)).isTrue();assertThat(two.get(5,TimeUnit.SECONDS)).isTrue();
            } finally { guarded.close(); }
        }
    }
    @Test void aRenewalCommittingPastTheOldDeadlineCannotOverbookQuota() throws Exception {
        jdbc.update("update app_settings set local_capacity_bytes=1048576 where id=1");
        byte[] data=bytes(700*1024);
        UploadRequest active=request(data),incoming=request(data);service.create(active,TOKEN,null);
        Instant oldDeadline=Instant.now().plusMillis(1500);
        jdbc.update("update upload_tasks set expires_at=? where id=?",Timestamp.from(oldDeadline),active.id());
        // Force an in-flight renewal to remain invisible until after its previously published expiry.
        jdbc.execute("create function slow_upload_renewal() returns trigger language plpgsql as $$ begin perform pg_sleep(2);return NEW;end $$");
        jdbc.execute("create trigger slow_upload_renewal before update of expires_at on upload_tasks for each row execute function slow_upload_renewal()");
        try(var worker=Executors.newSingleThreadExecutor()) {
            Future<Boolean> renewing=worker.submit(() -> coordinator.renewUpload(active.id(),Duration.ofMinutes(10)));
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(!Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_stat_activity where wait_event='PgSleep' and datname=current_database())",Boolean.class))) {
                if(System.nanoTime()>limit) throw new AssertionError("The renewal did not enter its delayed update");
                Thread.sleep(10);
            }
            long wait=Duration.between(Instant.now(),oldDeadline.plusMillis(50)).toMillis();
            if(wait>0) Thread.sleep(wait);
            error(HttpStatus.INSUFFICIENT_STORAGE,() -> service.create(incoming,TOKEN,null));
            assertThat(renewing.get(5,TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.reservedUploadBytes(null)).isEqualTo(data.length);
            assertThat(tasks.find(incoming.id())).isNull();
        } finally {
            jdbc.execute("drop trigger slow_upload_renewal on upload_tasks");
            jdbc.execute("drop function slow_upload_renewal()");
        }
    }
    @Test void upgradingOldDeadlinesPreservesActivityTimeAndTerminalResults() {
        String oldSchema="upload_migration_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("create schema "+oldSchema);
        String url=System.getenv("TEST_POSTGRES_URL");
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+oldSchema,
                System.getenv().getOrDefault("TEST_POSTGRES_USERNAME","clipboard_test"),
                System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD","clipboard_test"));
        try {
            Flyway.configure().dataSource(source).schemas(oldSchema).defaultSchema(oldSchema).target("5").load().migrate();
            var old=new JdbcTemplate(source);
            Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
            UUID recent=UUID.randomUUID(),inactive=UUID.randomUUID();
            Map<UUID,Instant> unchanged=new HashMap<>();
            for(String state:List.of("UPLOADING","COMPLETED","CANCELLED","EXPIRED")) {
                UUID id=state.equals("UPLOADING")?recent:UUID.randomUUID();
                Instant expiry=now.plusSeconds(state.equals("UPLOADING")?86400:604800);
                old.update("insert into upload_tasks(id,token_hash,original_name,content_type,size_bytes,permanent,chunk_hashes,state,expires_at,file_snapshot) "
                                + "values(?,?,'file','text/plain',1,false,'[]',?,?,?)",id,"a".repeat(64),state,Timestamp.from(expiry),state.equals("COMPLETED")?"{}":null);
                if(!state.equals("UPLOADING")) unchanged.put(id,expiry);
            }
            old.update("insert into upload_tasks(id,token_hash,original_name,content_type,size_bytes,permanent,chunk_hashes,expires_at) "
                    + "values(?,?,'file','text/plain',1,false,'[]',?)",inactive,"b".repeat(64),Timestamp.from(now.plusSeconds(86400-1200)));
            Flyway.configure().dataSource(source).schemas(oldSchema).defaultSchema(oldSchema).load().migrate();
            assertThat(old.queryForObject("select allow_anonymous_uploads from app_settings where id=1",Boolean.class)).isTrue();
            assertThat(old.queryForObject("select registration_enabled from app_settings where id=1",Boolean.class)).isTrue();
            assertThat(old.queryForObject("select expires_at from upload_tasks where id=?",Timestamp.class,recent).toInstant()).isEqualTo(now.plusSeconds(600));
            assertThat(old.queryForObject("select expires_at from upload_tasks where id=?",Timestamp.class,inactive).toInstant()).isEqualTo(now.minusSeconds(600));
            for(var entry:unchanged.entrySet())
                assertThat(old.queryForObject("select expires_at from upload_tasks where id=?",Timestamp.class,entry.getKey()).toInstant()).isEqualTo(entry.getValue());
        } finally { admin.execute("drop schema "+oldSchema+" cascade"); }
    }
    @Test void tokenAndOwnerAreVerifiedBeforeReadingBodyAndMetadataCannotChange() {
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        jdbc.update("insert into app_users(id,username,password_hash,role) values (?,'owner','hash','USER'),(?,'other','hash','USER')",owner,other);
        AppPrincipal principal=new AppPrincipal(owner,"owner","USER"),stranger=new AppPrincipal(other,"other","USER");
        byte[] data=bytes(10);UploadRequest request=request(UUID.randomUUID(),data,true);service.create(request,TOKEN,principal);
        InputStream forbidden=new InputStream() { public int read() { throw new AssertionError("Unauthorized request read its body"); } };
        error(HttpStatus.NOT_FOUND,() -> service.chunk(request.id(),0,TOKEN,stranger,forbidden,-1));
        error(HttpStatus.NOT_FOUND,() -> service.chunk(request.id(),0,TOKEN,null,forbidden,-1));
        error(HttpStatus.NOT_FOUND,() -> service.get(request.id(),"b".repeat(64),principal));
        var changed=new UploadRequest(request.id(),"different.bin",request.contentType(),request.sizeBytes(),true,request.chunkHashes());
        error(HttpStatus.CONFLICT,() -> service.create(changed,TOKEN,principal));
        service.chunk(request.id(),0,TOKEN,principal,new ByteArrayInputStream(data),data.length);
        jdbc.update("delete from app_users where id=?",owner);service.maintain();
        assertThat(tasks.find(request.id())).isNull();
        assertThat(Files.exists(parts.directory(request.id()))).isFalse();
    }
    @Test void completedCancellationAndExistingFileIdCollisionNeverDeleteTheFile() throws Exception {
        byte[] data=bytes(1024);UploadRequest request=request(data);upload(request,data);
        UploadStatus done=service.complete(request.id(),TOKEN,null);
        error(HttpStatus.CONFLICT,() -> service.cancel(request.id(),TOKEN,null));
        tasks.delete(request.id());
        error(HttpStatus.CONFLICT,() -> service.create(request,TOKEN,null));
        assertThat(files.find(done.file().id())).isPresent();
        assertThat(Files.readAllBytes(local.path(request.id().toString()))).isEqualTo(data);
        assertThatThrownBy(() -> local.write(request.id().toString(),new ByteArrayInputStream(new byte[1]),1)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(local.path(request.id().toString()))).isEqualTo(data);
    }
    @Test void sameLengthDiskCorruptionIsRejectedAndMissingChunkCanBeReuploaded() throws Exception {
        byte[] data=bytes(1024);UploadRequest request=request(data);upload(request,data);
        Files.write(parts.part(request.id(),0),new byte[data.length]);
        error(HttpStatus.CONFLICT,() -> service.complete(request.id(),TOKEN,null));
        assertThat(service.get(request.id(),TOKEN,null).receivedChunks()).isEmpty();
        chunk(request,0,data);
        assertThat(service.complete(request.id(),TOKEN,null).state()).isEqualTo("COMPLETED");
    }
    @Test void loweringUploadLimitRejectsCompletionWithoutLosingParts() {
        byte[] data=bytes(2*1048576);UploadRequest request=request(data);upload(request,data);
        settings.save(new StorageSettings(64L*1048576,50,1048576L));
        error(HttpStatus.PAYLOAD_TOO_LARGE,() -> service.complete(request.id(),TOKEN,null));
        assertThat(service.get(request.id(),TOKEN,null).receivedChunks()).containsExactly(0);
        settings.save(new StorageSettings(64L*1048576,50,32L*1048576));
        assertThat(service.complete(request.id(),TOKEN,null).state()).isEqualTo("COMPLETED");
    }
}
