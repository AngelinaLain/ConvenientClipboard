package com.convenientclipboard.files;

import com.convenientclipboard.auth.AppPrincipal;
import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import com.convenientclipboard.storage.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileServiceTest {
    @TempDir Path directory;
    FileRepository repository;
    StorageCoordinator coordinator;
    StorageAccess access;
    SettingsService settings;
    LocalStorage local;
    FileService service;
    ApplicationEventPublisher events;
    AppPrincipal owner=new AppPrincipal(UUID.randomUUID(),"owner","USER");
    @BeforeEach void setup() throws Exception {
        var properties=new AppProperties("https://files.example",directory,4*1048576L,"j".repeat(32),"admin","password123","k".repeat(32));
        repository=mock(FileRepository.class);coordinator=mock(StorageCoordinator.class);access=mock(StorageAccess.class);settings=mock(SettingsService.class);events=mock(ApplicationEventPublisher.class);
        when(coordinator.locked(any())).thenAnswer(invocation->((Supplier<?>)invocation.getArgument(0)).get());
        when(settings.get()).thenReturn(new StorageSettings(1024,50,1048576L));
        local=new LocalStorage(properties);service=new FileService(repository,local,access,settings,coordinator,properties,events);
    }
    @Test void anonymousUploadAlwaysExpiresSevenDaysAndSanitizesFilename() {
        Instant before=Instant.now();
        FileInfo result=service.upload(new MockMultipartFile("file","../../hello\r\n.txt","text/plain",new byte[]{1,2,3}),false,null);
        assertFalse(result.permanent());assertEquals("hello__.txt",result.originalName());
        assertTrue(result.expiresAt().isAfter(before.plusSeconds(604799)));assertTrue(result.expiresAt().isBefore(Instant.now().plusSeconds(604801)));
        assertEquals(3,local.usedBytes());assertTrue(result.shareUrl().matches("https://files.example/s/[A-Za-z0-9_-]{32}"));
    }
    @Test void anonymousCannotCreatePermanentUpload() {
        ApiException ex=assertThrows(ApiException.class,()->service.upload(new MockMultipartFile("file",new byte[1]),true,null));
        assertEquals(HttpStatus.UNAUTHORIZED,ex.status());assertEquals(0,local.usedBytes());verify(repository,never()).insert(any());
    }
    @Test void hardCapacityRejectsBeforeWritingAndPermanentRequiresExplicitChoice() {
        var upload=new MockMultipartFile("file","a.bin","application/octet-stream",new byte[700]);
        assertFalse(service.upload(upload,false,owner).permanent());
        ApiException ex=assertThrows(ApiException.class,()->service.upload(upload,true,owner));
        assertEquals(HttpStatus.INSUFFICIENT_STORAGE,ex.status());assertEquals(700,local.usedBytes());
    }
    @Test void failedDatabaseInsertRemovesUncommittedLocalFile() {
        doThrow(new IllegalStateException("db")).when(repository).insert(any());
        assertThrows(IllegalStateException.class,()->service.upload(new MockMultipartFile("file",new byte[4]),false,null));
        assertEquals(0,local.usedBytes());
    }
    @Test void failedPostCommitEventDoesNotDestroyCommittedUpload() {
        doThrow(new IllegalStateException("queue")).when(events).publishEvent(any(StorageChanged.class));
        assertNotNull(service.upload(new MockMultipartFile("file",new byte[4]),false,null));assertEquals(4,local.usedBytes());
    }
    private StoredFile file(Instant expiry) { return new StoredFile(UUID.randomUUID(),owner.id(),"x.txt","text/plain",3,UUID.randomUUID().toString(),"a".repeat(32),false,expiry,"LOCAL",null,Instant.now()); }
    @Test void expiredLinkCannotDownloadOrBecomePermanent() {
        StoredFile file=file(Instant.now().minusSeconds(1));
        when(repository.byShare(file.shareToken())).thenReturn(Optional.of(file));when(repository.find(file.id())).thenReturn(Optional.of(file));
        assertEquals(HttpStatus.GONE,assertThrows(ApiException.class,()->service.download(file.shareToken())).status());
        assertEquals(HttpStatus.GONE,assertThrows(ApiException.class,()->service.retention(file.id(),true,owner)).status());
        verifyNoInteractions(access);
    }
    @Test void anotherUserCannotDeleteAndFailedStorageDeleteKeepsRecord() throws Exception {
        StoredFile file=file(Instant.now().plusSeconds(60));when(repository.find(file.id())).thenReturn(Optional.of(file));
        AppPrincipal stranger=new AppPrincipal(UUID.randomUUID(),"stranger","USER");
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,()->service.delete(file.id(),stranger)).status());
        doThrow(new java.io.IOException("storage unavailable")).when(access).delete(file);
        assertEquals(HttpStatus.BAD_GATEWAY,assertThrows(ApiException.class,()->service.delete(file.id(),owner)).status());verify(repository,never()).delete(any());
    }
    @Test void administratorCanDeleteAnotherUsersFileAfterStorageRemoval() throws Exception {
        StoredFile file=file(Instant.now().plusSeconds(60));when(repository.find(file.id())).thenReturn(Optional.of(file));
        service.delete(file.id(),new AppPrincipal(UUID.randomUUID(),"admin","ADMIN"));
        var order=inOrder(access,repository);order.verify(access).delete(file);order.verify(repository).delete(file.id());
    }
    @Test void changingLimitTakesEffectImmediatelyAndIgnoresBootstrapLimit() {
        long mib=1048576L;
        var upload=new MockMultipartFile("file","dynamic.bin","application/octet-stream",new byte[(int)(mib+mib/2)]);
        when(settings.get()).thenReturn(new StorageSettings(16*mib,50,mib));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,assertThrows(ApiException.class,()->service.upload(upload,false,owner)).status());
        assertEquals(0,local.usedBytes());
        when(settings.get()).thenReturn(new StorageSettings(16*mib,50,2*mib));
        FileInfo accepted=service.upload(upload,false,owner);
        assertEquals(upload.getSize(),accepted.sizeBytes());
        when(settings.get()).thenReturn(new StorageSettings(16*mib,50,mib));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,assertThrows(ApiException.class,()->service.upload(upload,false,owner)).status());
        assertEquals(upload.getSize(),local.usedBytes(),"Lowering the limit must preserve existing larger files");
        verify(settings,times(3)).get();
        verify(repository,times(1)).insert(any());
    }
    @Test void streamedBytesAlsoUseCurrentDynamicLimit() {
        long mib=1048576L;
        when(settings.get()).thenReturn(new StorageSettings(16*mib,50,mib));
        var dishonest=new MockMultipartFile("file","stream.bin","application/octet-stream",new byte[(int)mib+1]) {
            @Override public long getSize() { return 1; }
        };
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,assertThrows(ApiException.class,()->service.upload(dishonest,false,owner)).status());
        assertEquals(0,local.usedBytes());verify(repository,never()).insert(any());
        verify(settings,times(1)).get();
    }
}
