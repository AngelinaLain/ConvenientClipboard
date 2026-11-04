package com.convenientclipboard.storage;

import com.convenientclipboard.files.FileRepository;
import com.convenientclipboard.files.StoredFile;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageMaintenanceTest {
    @Test void failedProviderRetainsLocalFileAndFallsBackBeforeMetadataSwitch() throws Exception {
        FileRepository files=mock(FileRepository.class);LocalStorage local=mock(LocalStorage.class);StorageAccess access=mock(StorageAccess.class);RemoteStorage remote=mock(RemoteStorage.class);
        ProviderService providers=mock(ProviderService.class);SettingsService settings=mock(SettingsService.class);StorageCoordinator lock=mock(StorageCoordinator.class);
        when(lock.locked(any())).thenAnswer(invocation->((Supplier<?>)invocation.getArgument(0)).get());
        StoredFile file=new StoredFile(UUID.randomUUID(),null,"x","text/plain",600,"a".repeat(36),"x".repeat(32),false,Instant.now().plusSeconds(100),"LOCAL",null,Instant.now());
        StorageProvider first=new StorageProvider(UUID.randomUUID(),"first","http://localhost","us-east-1","bucket","a","b",true,1);
        StorageProvider second=new StorageProvider(UUID.randomUUID(),"second","http://localhost","us-east-1","bucket","a","b",true,2);
        when(files.expired()).thenReturn(List.of());when(local.objects()).thenReturn(List.of());when(files.localCandidates()).thenReturn(List.of(file));when(files.find(file.id())).thenReturn(Optional.of(file));
        when(settings.get()).thenReturn(new StorageSettings(1000,50));when(local.usedBytes()).thenReturn(600L);when(local.path(file.objectKey())).thenReturn(Path.of("source"));when(providers.enabled()).thenReturn(List.of(first,second));
        doThrow(new IllegalStateException("offline")).when(remote).uploadVerified(eq(first),anyString(),any(),anyLong());
        StorageMaintenance maintenance=new StorageMaintenance(files,local,access,remote,providers,settings,lock);
        try { maintenance.runMaintenance(); } finally { maintenance.shutdown(); }
        var order=inOrder(remote,files,local);
        order.verify(remote).uploadVerified(eq(first),eq(file.objectKey()),any(),eq(600L));
        order.verify(remote).uploadVerified(eq(second),eq(file.objectKey()),any(),eq(600L));
        order.verify(files).setRemote(file.id(),second.id());order.verify(local).delete(file.objectKey());
    }
    @Test void allProvidersFailWithoutDeletingLocalOrUpdatingMetadata() throws Exception {
        FileRepository files=mock(FileRepository.class);LocalStorage local=mock(LocalStorage.class);StorageAccess access=mock(StorageAccess.class);RemoteStorage remote=mock(RemoteStorage.class);
        ProviderService providers=mock(ProviderService.class);SettingsService settings=mock(SettingsService.class);StorageCoordinator lock=mock(StorageCoordinator.class);
        when(lock.locked(any())).thenAnswer(invocation->((Supplier<?>)invocation.getArgument(0)).get());
        StoredFile file=new StoredFile(UUID.randomUUID(),null,"x","text/plain",600,"a".repeat(36),"x".repeat(32),false,Instant.now().plusSeconds(100),"LOCAL",null,Instant.now());
        StorageProvider provider=new StorageProvider(UUID.randomUUID(),"offline","http://localhost","us-east-1","bucket","a","b",true,1);
        when(files.expired()).thenReturn(List.of());when(local.objects()).thenReturn(List.of());when(files.localCandidates()).thenReturn(List.of(file));when(files.find(file.id())).thenReturn(Optional.of(file));
        when(settings.get()).thenReturn(new StorageSettings(1000,50));when(local.usedBytes()).thenReturn(600L);when(local.path(file.objectKey())).thenReturn(Path.of("source"));when(providers.enabled()).thenReturn(List.of(provider));
        doThrow(new IllegalStateException("offline")).when(remote).uploadVerified(any(),anyString(),any(),anyLong());
        StorageMaintenance maintenance=new StorageMaintenance(files,local,access,remote,providers,settings,lock);
        try { maintenance.runMaintenance(); } finally { maintenance.shutdown(); }
        verify(files,never()).setRemote(any(),any());verify(local,never()).delete(anyString());
    }
}
