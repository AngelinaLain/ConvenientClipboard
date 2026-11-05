package com.convenientclipboard.admin;

import com.convenientclipboard.auth.AppPrincipal;
import com.convenientclipboard.auth.PasswordPolicy;
import com.convenientclipboard.auth.UserRepository;
import com.convenientclipboard.common.ApiException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserAdminService {
    public record UserInfo(UUID id, String username, String role, boolean enabled, Instant createdAt,
                           long fileCount, long storageBytes) {
    }

    public record UserPage(List<UserInfo> items, int page, int size, long total) {
    }

    private static final RowMapper<UserInfo> MAPPER = (rs, n) -> new UserInfo(
            rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("role"), rs.getBoolean("enabled"),
            rs.getTimestamp("created_at").toInstant(), rs.getLong("file_count"), rs.getLong("storage_bytes"));
    private static final String USER_INFO = "select u.id,u.username,u.role,u.enabled,u.created_at,"
            + "coalesce(f.file_count,0) file_count,coalesce(f.storage_bytes,0) storage_bytes from app_users u "
            + "left join (select owner_id,count(*) file_count,sum(size_bytes) storage_bytes from stored_files group by owner_id) f on f.owner_id=u.id ";
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;

    public UserAdminService(JdbcTemplate jdbc, UserRepository users, PasswordEncoder passwords, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwords = passwords;
        this.transactions = transactions;
    }

    public UserPage list(int page, int size, String query) {
        if (page < 0 || size < 1 || size > 100 || query == null || query.length() > 64)
            throw new ApiException(HttpStatus.BAD_REQUEST, "分页或搜索参数无效");
        String filter = "%" + query.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String where = "where lower(u.username) like ? escape '!' ";
        long total = jdbc.queryForObject("select count(*) from app_users u " + where, Long.class, filter);
        var items = jdbc.query(USER_INFO + where + "order by u.created_at desc,u.id limit ? offset ?", MAPPER, filter, size, (long) page * size);
        return new UserPage(items, page, size, total);
    }

    public UserInfo create(String username, String password, String role, AppPrincipal actor) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,32}"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "用户名使用 3–32 位字母、数字或下划线");
        requireRole(role);
        PasswordPolicy.validate(password);
        String hash = passwords.encode(password);
        return locked(actor, () -> {
            try {
                var user = users.create(username.toLowerCase(Locale.ROOT), hash, role);
                return info(user.id());
            } catch (DuplicateKeyException ex) {
                throw new ApiException(HttpStatus.CONFLICT, "用户名已被使用");
            }
        });
    }

    public UserInfo update(UUID id, String role, boolean enabled, AppPrincipal actor) {
        requireRole(role);
        return locked(actor, () -> {
            var user = requireUser(id);
            if (id.equals(actor.id()) && (!enabled || !"ADMIN".equals(role)))
                throw new ApiException(HttpStatus.CONFLICT, "不能禁用或降级自己的管理员账号");
            protectLastAdmin(user, enabled && "ADMIN".equals(role));
            if (!user.role().equals(role) || user.enabled() != enabled)
                jdbc.update("update app_users set role=?,enabled=?,auth_version=auth_version+1 where id=?", role, enabled, id);
            return info(id);
        });
    }

    public void resetPassword(UUID id, String password, AppPrincipal actor) {
        PasswordPolicy.validate(password);
        String hash = passwords.encode(password);
        locked(actor, () -> {
            requireUser(id);
            jdbc.update("update app_users set password_hash=?,auth_version=auth_version+1 where id=?", hash, id);
            return null;
        });
    }

    public void delete(UUID id, AppPrincipal actor) {
        try {
            locked(actor, () -> {
                var user = requireUser(id);
                if (id.equals(actor.id())) throw new ApiException(HttpStatus.CONFLICT, "不能删除自己的管理员账号");
                protectLastAdmin(user, false);
                if (jdbc.queryForObject("select count(*) from stored_files where owner_id=?", Long.class, id) > 0)
                    throw new ApiException(HttpStatus.CONFLICT, "该用户仍有文件，请先删除或转移文件后再删除用户");
                jdbc.update("delete from app_users where id=?", id);
                return null;
            });
        } catch (DataIntegrityViolationException ex) {
            // An upload racing this deletion may have inserted a file after the count check.
            throw new ApiException(HttpStatus.CONFLICT, "该用户仍有文件，请先删除或转移文件后再删除用户");
        }
    }

    private UserRepository.User requireUser(UUID id) {
        return users.byId(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private UserInfo info(UUID id) {
        return jdbc.query(USER_INFO + "where u.id=?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private void protectLastAdmin(UserRepository.User user, boolean remainsAdmin) {
        if (user.enabled() && "ADMIN".equals(user.role()) && !remainsAdmin
                && jdbc.queryForObject("select count(*) from app_users where enabled and role='ADMIN'", Long.class) <= 1)
            throw new ApiException(HttpStatus.CONFLICT, "至少需要保留一个启用的管理员账号");
    }

    private static void requireRole(String role) {
        if (!"USER".equals(role) && !"ADMIN".equals(role)) throw new ApiException(HttpStatus.BAD_REQUEST, "角色无效");
    }

    private <T> T locked(AppPrincipal actor, Supplier<T> operation) {
        return transactions.execute(status -> {
            jdbc.execute("select pg_advisory_xact_lock(787459132)");
            if (actor == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
            var current = requireUser(actor.id());
            if (!current.enabled() || !"ADMIN".equals(current.role()))
                throw new ApiException(HttpStatus.FORBIDDEN, "管理员权限已失效，请重新登录");
            return operation.get();
        });
    }
}
