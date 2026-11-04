package com.convenientclipboard.storage;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@Component
public class RemoteStorage {
    private final CredentialCipher cipher;
    private final MountedStorage mounted;
    public RemoteStorage(CredentialCipher cipher,MountedStorage mounted) { this.cipher=cipher;this.mounted=mounted; }
    private S3Client client(StorageProvider provider) {
        return S3Client.builder().endpointOverride(URI.create(provider.endpoint())).region(Region.of(provider.region()))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cipher.decrypt(provider.accessKeyEncrypted()),cipher.decrypt(provider.secretKeyEncrypted()))))
            .forcePathStyle(true)
            .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallAttemptTimeout(Duration.ofSeconds(60)).apiCallTimeout(Duration.ofMinutes(5)).build()).build();
    }
    public void uploadVerified(StorageProvider provider,String key,Path file,long expectedSize) {
        if(provider.mounted()) {
            try { mounted.uploadVerified(provider,key,file,expectedSize);return; }
            catch(IOException ex) { throw new IllegalStateException("Mounted storage write failed",ex); }
        }
        try(S3Client client=client(provider)) {
            client.putObject(PutObjectRequest.builder().bucket(provider.bucket()).key(key).contentType("application/octet-stream").build(),RequestBody.fromFile(file));
            long size=client.headObject(HeadObjectRequest.builder().bucket(provider.bucket()).key(key).build()).contentLength();
            if(size!=expectedSize) throw new IllegalStateException("Remote file size verification failed");
        }
    }
    public InputStream open(StorageProvider provider,String key) throws IOException {
        if(provider.mounted()) return mounted.open(provider,key);
        S3Client client=client(provider);
        try {
            InputStream stream=client.getObject(GetObjectRequest.builder().bucket(provider.bucket()).key(key).build());
            return new FilterInputStream(stream) {
                @Override public void close() throws IOException { try { super.close(); } finally { client.close(); } }
            };
        } catch(RuntimeException ex) { client.close(); throw ex; }
    }
    public void delete(StorageProvider provider,String key) throws IOException {
        if(provider.mounted()) { mounted.delete(provider,key);return; }
        try(S3Client client=client(provider)) { client.deleteObject(DeleteObjectRequest.builder().bucket(provider.bucket()).key(key).build()); }
    }
}
