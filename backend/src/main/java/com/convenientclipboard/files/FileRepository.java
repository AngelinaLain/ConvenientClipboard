package com.convenientclipboard.files;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FileRepository {
    private final JdbcTemplate jdbc;
    public FileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Instant instant(ResultSet rs, String field) throws SQLException {
        var timestamp = rs.getTimestamp(field);
        return timestamp == null ? null : timestamp.toInstant();
    }
    private static final RowMapper<StoredFile> MAPPER = (rs, row) -> new StoredFile(
        rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("original_name"),
        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("object_key"),
        rs.getString("share_token"), rs.getBoolean("permanent"), instant(rs,"expires_at"),
        rs.getString("location"), rs.getObject("provider_id", UUID.class), instant(rs,"created_at"));
    public Optional<StoredFile> find(UUID id) { return jdbc.query("select * from stored_files where id=?", MAPPER,id).stream().findFirst(); }
    public Optional<StoredFile> byShare(String token) { return jdbc.query("select * from stored_files where share_token=?", MAPPER, token).stream().findFirst(); }
    public List<StoredFile> owned(UUID owner) { return jdbc.query("select * from stored_files where owner_id=? and (permanent or expires_at>now()) order by created_at desc",MAPPER,owner); }
    public List<StoredFile> all() { return jdbc.query("select * from stored_files order by created_at desc limit 1000",MAPPER); }
    public record AdminEntry(StoredFile file,String ownerName) {}
    private static final String ADMIN_FILTER=" from stored_files f left join app_users u on u.id=f.owner_id where (?='' or strpos(lower(f.original_name),lower(?))>0 or strpos(lower(coalesce(u.username,'')),lower(?))>0 or strpos(f.id::text,lower(?))>0)";
    public List<AdminEntry> adminPage(int page,int size,String query) {
        return jdbc.query("select f.*,u.username as owner_name"+ADMIN_FILTER+" order by f.created_at desc,f.id desc limit ? offset ?",
            (rs,n)->new AdminEntry(MAPPER.mapRow(rs,n),rs.getString("owner_name")),query,query,query,query,size,(long)page*size);
    }
    public long adminCount(String query) { return jdbc.queryForObject("select count(*)"+ADMIN_FILTER,Long.class,query,query,query,query); }
    public List<StoredFile> localCandidates() { return jdbc.query("select * from stored_files where location='LOCAL' and (permanent or expires_at>now()) order by created_at limit 100",MAPPER); }
    public List<StoredFile> expired() { return jdbc.query("select * from stored_files where not permanent and expires_at<=now() order by expires_at limit 100",MAPPER); }
    public void insert(StoredFile f) {
        jdbc.update("insert into stored_files(id,owner_id,original_name,content_type,size_bytes,object_key,share_token,permanent,expires_at,location,provider_id,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
            f.id(), f.ownerId(), f.originalName(), f.contentType(), f.sizeBytes(), f.objectKey(), f.shareToken(), f.permanent(),
            f.expiresAt()==null?null:java.sql.Timestamp.from(f.expiresAt()),f.location(),f.providerId(),java.sql.Timestamp.from(f.createdAt()));
    }
    public void setRemote(UUID id, UUID provider) { jdbc.update("update stored_files f set location=p.type,provider_id=p.id from storage_providers p where f.id=? and p.id=?",id,provider); }
    public void retention(UUID id, boolean permanent, Instant expiry) { jdbc.update("update stored_files set permanent=?,expires_at=? where id=?",permanent,expiry==null?null:java.sql.Timestamp.from(expiry),id); }
    public void delete(UUID id) { jdbc.update("delete from stored_files where id=?",id); }
    public long total() { return jdbc.queryForObject("select count(*) from stored_files",Long.class); }
    public long permanentCount() { return jdbc.queryForObject("select count(*) from stored_files where permanent",Long.class); }
    public long remoteBytes() { return jdbc.queryForObject("select coalesce(sum(size_bytes),0) from stored_files where location<>'LOCAL'",Long.class); }
    public boolean providerUsed(UUID id) { return jdbc.queryForObject("select count(*) from stored_files where provider_id=?",Long.class,id)>0; }
    public String ownerName(UUID id) { return id==null?null:jdbc.queryForObject("select username from app_users where id=?",String.class,id); }
}
