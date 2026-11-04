package com.convenientclipboard.storage;

import com.convenientclipboard.files.FileRepository;
import com.convenientclipboard.files.StoredFile;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class StorageMaintenance {
    private static final Logger log=LoggerFactory.getLogger(StorageMaintenance.class);
    private final FileRepository files;
    private final LocalStorage local;
    private final StorageAccess storage;
    private final RemoteStorage remote;
    private final ProviderService providers;
    private final SettingsService settings;
    private final StorageCoordinator coordinator;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("storage-maintenance-",0).factory());
    private final AtomicBoolean running=new AtomicBoolean();
    public StorageMaintenance(FileRepository files,LocalStorage local,StorageAccess storage,RemoteStorage remote,ProviderService providers,SettingsService settings,StorageCoordinator coordinator) {
        this.files=files;this.local=local;this.storage=storage;this.remote=remote;this.providers=providers;this.settings=settings;this.coordinator=coordinator;
    }
    @EventListener public void changed(StorageChanged event) { request(); }
    @Scheduled(fixedDelayString="${app.maintenance-delay-ms:30000}") public void scheduled() { request(); }
    public void request() {
        if(running.compareAndSet(false,true)) worker.submit(()-> {
            try { runMaintenance(); }
            catch(RuntimeException ex) { log.warn("Storage maintenance failed; will retry",ex); }
            finally { running.set(false); }
        });
    }
    public boolean running() { return running.get(); }
    void runMaintenance() {
        for(StoredFile expired:files.expired()) {
            try { coordinator.locked(()-> {
                StoredFile current=files.find(expired.id()).orElse(null);
                if(current==null || !current.expired(Instant.now())) return null;
                try { storage.delete(current); }
                catch(IOException ex) { throw new IllegalStateException(ex); }
                files.delete(current.id()); return null;
            }); } catch(RuntimeException ex) { log.warn("Expired file {} could not be removed; retaining record for retry",expired.id()); }
        }
        // Recover a crash between metadata commit and local deletion; uncommitted uploads are protected by the same lock.
        coordinator.locked(()-> {
            try { for(var path:local.objects()) {
                String key=path.getFileName().toString();
                if(!key.matches("[a-f0-9-]{36}")) continue;
                StoredFile file=files.find(UUID.fromString(key)).orElse(null);
                if(file==null || !"LOCAL".equals(file.location())) local.delete(key);
            } } catch(IOException ex) { log.warn("Local orphan cleanup will retry"); }
            return null;
        });
        for(StoredFile candidate:files.localCandidates()) {
            String migrated=coordinator.locked(()-> {
                StorageSettings limits=settings.get();
                if(local.usedBytes()<thresholdBytes(limits)) return null;
                StoredFile current=files.find(candidate.id()).orElse(null);
                if(current==null || !"LOCAL".equals(current.location()) || current.expired(Instant.now())) return "skip";
                for(StorageProvider provider:providers.enabled()) {
                    try { remote.uploadVerified(provider,current.objectKey(),local.path(current.objectKey()),current.sizeBytes()); }
                    catch(RuntimeException ex) { log.warn("Offload of file {} to provider {} failed; local copy retained",current.id(),provider.id()); continue; }
                    // A DB failure must roll back the transaction, never be swallowed as a provider failure.
                    files.setRemote(current.id(),provider.id());
                    return current.objectKey();
                }
                return null;
            });
            if(migrated==null) break;
            if(!migrated.equals("skip")) {
                try { local.delete(migrated); }
                catch(IOException ex) { log.warn("Local duplicate {} will be removed by next maintenance pass",migrated); }
            }
        }
    }
    static long thresholdBytes(StorageSettings settings) { return settings.localCapacityBytes()/100*settings.offloadThreshold(); }
    @PreDestroy public void shutdown() { worker.shutdownNow(); }
}
