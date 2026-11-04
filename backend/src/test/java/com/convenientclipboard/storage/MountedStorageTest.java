package com.convenientclipboard.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MountedStorageTest {
    @TempDir Path temporary;
    private long count(Path directory) throws IOException { try(var entries=Files.list(directory)) { return entries.count(); } }
    private StorageProvider provider(Path path,String type,String mountId) { return new StorageProvider(UUID.randomUUID(),"test",null,null,null,null,null,true,1,type,path.toString(),mountId); }
    @Test void nfsAndSmbCopyReadAndDeleteWithoutLocalSourceLoss() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("remote"));
        MountedStorage storage=new MountedStorage(root.toString());
        byte[] payload=new byte[700*1024];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i%251);
        Path source=temporary.resolve("source");Files.write(source,payload);
        for(String type:new String[]{"NFS","SMB"}) {
            Path share=Files.createDirectory(root.resolve(type));Files.writeString(share.resolve(MountedStorage.MARKER),"share-"+type+"\n");
            StorageProvider provider=provider(share,type,"share-"+type);String key=UUID.randomUUID().toString();
            storage.uploadVerified(provider,key,source,payload.length);
            assertArrayEquals(payload,Files.readAllBytes(source));
            try(var input=storage.open(provider,key)) { assertArrayEquals(payload,input.readAllBytes()); }
            assertEquals(2,count(share),"Only the marker and complete object should remain");
            storage.delete(provider,key);assertFalse(Files.exists(share.resolve(key)));assertTrue(Files.exists(share.resolve(MountedStorage.MARKER)));
        }
    }
    @Test void missingOrMismatchedMountMarkerNeverCreatesOrWritesDirectory() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("remote"));Path share=Files.createDirectory(root.resolve("share"));
        MountedStorage storage=new MountedStorage(root.toString());StorageProvider provider=provider(share,"NFS","expected");
        Path source=Files.writeString(temporary.resolve("source"),"data");String key=UUID.randomUUID().toString();
        assertThrows(IOException.class,()->storage.uploadVerified(provider,key,source,4));
        assertEquals(0,count(share));
        Files.writeString(share.resolve(MountedStorage.MARKER),"different");
        assertThrows(IOException.class,()->storage.uploadVerified(provider,key,source,4));
        assertThrows(IOException.class,()->storage.open(provider,key));assertThrows(IOException.class,()->storage.delete(provider,key));
        Path missing=root.resolve("not-mounted");assertThrows(IOException.class,()->storage.validate(provider(missing,"SMB","expected")));assertFalse(Files.exists(missing));
    }
    @Test void traversalOutsideAllowedRootAndObjectTraversalAreRejected() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("remote"));Path outside=Files.createDirectory(temporary.resolve("outside"));Files.writeString(outside.resolve(MountedStorage.MARKER),"id");
        MountedStorage storage=new MountedStorage(root.toString());
        assertThrows(IOException.class,()->storage.validate(provider(outside,"NFS","id")));
        assertThrows(IOException.class,()->storage.validate(provider(root.resolve("../outside"),"SMB","id")));
        Files.writeString(root.resolve(MountedStorage.MARKER),"id");
        assertThrows(IOException.class,()->storage.open(provider(root,"NFS","id"),"../outside"));
    }
    @Test void symlinkedShareAndObjectCannotEscapeAllowedRoot() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("remote"));Path outside=Files.createDirectory(temporary.resolve("outside"));Files.writeString(outside.resolve(MountedStorage.MARKER),"id");
        Path link=root.resolve("escape");
        try { Files.createSymbolicLink(link,outside); }
        catch(IOException|UnsupportedOperationException|SecurityException ex) { assumeTrue(false,"OS account cannot create symlinks"); }
        MountedStorage storage=new MountedStorage(root.toString());assertThrows(IOException.class,()->storage.validate(provider(link,"NFS","id")));
        Path share=Files.createDirectory(root.resolve("share"));Files.writeString(share.resolve(MountedStorage.MARKER),"id");
        String key=UUID.randomUUID().toString();Files.createSymbolicLink(share.resolve(key),outside.resolve(MountedStorage.MARKER));
        assertThrows(IOException.class,()->storage.open(provider(share,"SMB","id"),key));
        assertThrows(IOException.class,()->storage.delete(provider(share,"SMB","id"),key));
        assertEquals("id",Files.readString(outside.resolve(MountedStorage.MARKER)));
    }
    @Test void sizeMismatchKeepsSourceAndPublishesNoObject() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("remote"));Files.writeString(root.resolve(MountedStorage.MARKER),"id");
        Path source=Files.writeString(temporary.resolve("source"),"source");MountedStorage storage=new MountedStorage(root.toString());String key=UUID.randomUUID().toString();
        assertThrows(IOException.class,()->storage.uploadVerified(provider(root,"NFS","id"),key,source,99));
        assertFalse(Files.exists(root.resolve(key)));assertEquals("source",Files.readString(source));assertEquals(1,count(root));
    }
}
