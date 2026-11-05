package com.convenientclipboard.uploads;

import com.convenientclipboard.files.FileInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UploadRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public UploadRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    private <T> T decode(String value, Class<T> type) {
        try { return value == null ? null : json.readValue(value,type); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid persisted upload result",ex); }
    }
    private List<String> hashes(String value) {
        try { return json.readValue(value,new TypeReference<List<String>>() {}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid persisted upload manifest",ex); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize upload metadata",ex); }
    }
    UploadTask find(UUID id) {
        return jdbc.query("select * from upload_tasks where id=?", (rs,n) -> new UploadTask(
                rs.getObject("id",UUID.class),rs.getObject("owner_id",UUID.class),rs.getString("token_hash"),
                rs.getString("original_name"),rs.getString("content_type"),rs.getLong("size_bytes"),rs.getBoolean("permanent"),
                hashes(rs.getString("chunk_hashes")),rs.getString("state"),rs.getTimestamp("expires_at").toInstant(),
                decode(rs.getString("file_snapshot"),FileInfo.class)),id).stream().findFirst().orElse(null);
    }
    void insert(UploadTask task) {
        jdbc.update("insert into upload_tasks(id,owner_id,token_hash,original_name,content_type,size_bytes,permanent,chunk_hashes,expires_at) values(?,?,?,?,?,?,?,?,?)",
                task.id(),task.ownerId(),task.tokenHash(),task.originalName(),task.contentType(),task.sizeBytes(),task.permanent(),
                encode(task.chunkHashes()),Timestamp.from(task.expiresAt()));
    }
    void cancelled(UUID id,UUID owner,String tokenHash,Instant expiry) {
        // A cancellation may arrive before its in-flight create request. Keep the ID/token tombstone.
        jdbc.update("insert into upload_tasks(id,owner_id,token_hash,original_name,content_type,size_bytes,permanent,chunk_hashes,state,expires_at) "
                + "values(?,?,?,'','application/octet-stream',1,false,'[]','CANCELLED',?)",id,owner,tokenHash,Timestamp.from(expiry));
    }
    List<Integer> received(UUID id) {
        return jdbc.query("select chunk_index from upload_chunks where task_id=? order by chunk_index", (rs,n)->rs.getInt(1),id);
    }
    void received(UploadTask task,int index) {
        jdbc.update("insert into upload_chunks(task_id,chunk_index,size_bytes,sha256) values(?,?,?,?) on conflict(task_id,chunk_index) do update set size_bytes=excluded.size_bytes,sha256=excluded.sha256",
                task.id(),index,task.chunkBytes(index),task.chunkHashes().get(index));
    }
    void forget(UUID id,int index) { jdbc.update("delete from upload_chunks where task_id=? and chunk_index=?",id,index); }
    void touch(UUID id,Instant expiry) { jdbc.update("update upload_tasks set expires_at=? where id=?",Timestamp.from(expiry),id); }
    void complete(UUID id,FileInfo file,Instant expiry) {
        jdbc.update("update upload_tasks set state='COMPLETED',file_snapshot=?,expires_at=? where id=?",encode(file),Timestamp.from(expiry),id);
        jdbc.update("delete from upload_chunks where task_id=?",id);
    }
    void terminal(UUID id,String state,Instant expiry) {
        jdbc.update("update upload_tasks set state=?,expires_at=? where id=?",state,Timestamp.from(expiry),id);
        jdbc.update("delete from upload_chunks where task_id=?",id);
    }
    List<UUID> cleanupCandidates() {
        return jdbc.query("select id from upload_tasks where state<>'UPLOADING' or expires_at<=clock_timestamp() order by expires_at limit 200",
                (rs,n)->rs.getObject(1,UUID.class));
    }
    void delete(UUID id) { jdbc.update("delete from upload_tasks where id=?",id); }
}
