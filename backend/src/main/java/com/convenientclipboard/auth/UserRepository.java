package com.convenientclipboard.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record User(UUID id, String username, String passwordHash, String role, boolean enabled, long authVersion) {
        public AppPrincipal principal() {
            return new AppPrincipal(id, username, role);
        }
    }

    private static final RowMapper<User> MAPPER = (rs, row) -> new User(
            rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
            rs.getString("role"), rs.getBoolean("enabled"), rs.getLong("auth_version"));

    public Optional<User> byUsername(String username) {
        return jdbc.query("select * from app_users where username = ?", MAPPER, username).stream().findFirst();
    }

    public Optional<User> byId(UUID id) {
        return jdbc.query("select * from app_users where id = ?", MAPPER, id).stream().findFirst();
    }

    public User create(String username, String hash, String role) {
        var user = new User(UUID.randomUUID(), username, hash, role, true, 0);
        jdbc.update("insert into app_users(id,username,password_hash,role) values (?,?,?,?)",
                user.id(), user.username(), user.passwordHash(), user.role());
        return user;
    }
}
