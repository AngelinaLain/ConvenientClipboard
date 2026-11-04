package com.convenientclipboard.storage;

import com.convenientclipboard.files.StoredFile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class StorageAccess {
    private final LocalStorage local;
    private final RemoteStorage remote;
    private final ProviderService providers;
    public StorageAccess(LocalStorage local,RemoteStorage remote,ProviderService providers) { this.local=local;this.remote=remote;this.providers=providers; }
    public InputStream open(StoredFile file) throws IOException {
        return "LOCAL".equals(file.location())?local.open(file.objectKey()):remote.open(providers.get(file.providerId()),file.objectKey());
    }
    public void delete(StoredFile file) throws IOException {
        if("LOCAL".equals(file.location())) local.delete(file.objectKey());
        else { remote.delete(providers.get(file.providerId()),file.objectKey()); local.delete(file.objectKey()); }
    }
}
