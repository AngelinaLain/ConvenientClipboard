package com.convenientclipboard.admin;

import com.convenientclipboard.auth.AppPrincipal;
import com.convenientclipboard.auth.UserRepository;
import com.convenientclipboard.common.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {
    @Mock JdbcTemplate jdbc;
    @Mock UserRepository users;
    @Mock PasswordEncoder passwords;
    @Mock TransactionTemplate transactions;
    UserAdminService service;
    AppPrincipal actor = new AppPrincipal(UUID.randomUUID(), "admin", "ADMIN");
    UUID other = UUID.randomUUID();

    @BeforeEach void setUp() {
        service = new UserAdminService(jdbc, users, passwords, transactions);
        when(transactions.execute(any())).thenAnswer(call -> {
            TransactionCallback<Object> callback = call.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(users.byId(actor.id())).thenReturn(Optional.of(user(actor.id(), "ADMIN", true, 0)));
    }
    private UserRepository.User user(UUID id, String role, boolean enabled, long version) {
        return new UserRepository.User(id, "account", "existing-hash", role, enabled, version);
    }
    private void conflict(Runnable operation, String message) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getMessage()).contains(message);
        });
    }
    @Test void administratorCannotDisableDemoteOrDeleteSelf() {
        conflict(() -> service.update(actor.id(), "ADMIN", false, actor), "自己的");
        conflict(() -> service.update(actor.id(), "USER", true, actor), "自己的");
        conflict(() -> service.delete(actor.id(), actor), "自己的");
        verify(jdbc, never()).update(anyString(), any(), any(), any());
        verify(jdbc, never()).update(eq("delete from app_users where id=?"), any(UUID.class));
    }
    @Test void lastEnabledAdministratorCannotBeDemoted() {
        when(users.byId(other)).thenReturn(Optional.of(user(other, "ADMIN", true, 0)));
        when(jdbc.queryForObject("select count(*) from app_users where enabled and role='ADMIN'", Long.class)).thenReturn(1L);
        conflict(() -> service.update(other, "USER", true, actor), "至少需要保留一个");
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }
    @Test void lastEnabledAdministratorCannotBeDeleted() {
        when(users.byId(other)).thenReturn(Optional.of(user(other, "ADMIN", true, 0)));
        when(jdbc.queryForObject("select count(*) from app_users where enabled and role='ADMIN'", Long.class)).thenReturn(1L);
        conflict(() -> service.delete(other, actor), "至少需要保留一个");
        verify(jdbc, never()).update(eq("delete from app_users where id=?"), any(UUID.class));
    }
    @Test void accountWithFilesCannotBeDeleted() {
        when(users.byId(other)).thenReturn(Optional.of(user(other, "USER", true, 0)));
        when(jdbc.queryForObject("select count(*) from stored_files where owner_id=?", Long.class, other)).thenReturn(3L);
        conflict(() -> service.delete(other, actor), "请先删除或转移文件");
        verify(jdbc, never()).update(eq("delete from app_users where id=?"), any(UUID.class));
    }
    @Test void passwordResetStoresHashAndRevokesExistingAuthenticationVersion() {
        when(users.byId(other)).thenReturn(Optional.of(user(other, "USER", true, 2)));
        when(passwords.encode("new-password-123")).thenReturn("new-password-hash");
        service.resetPassword(other, "new-password-123", actor);
        var sequence = inOrder(jdbc);
        sequence.verify(jdbc).execute("select pg_advisory_xact_lock(787459132)");
        sequence.verify(jdbc).update("update app_users set password_hash=?,auth_version=auth_version+1 where id=?", "new-password-hash", other);
    }
    @Test void anAdministratorRevokedWhileWaitingForTheLockCannotContinue() {
        when(users.byId(actor.id())).thenReturn(Optional.of(user(actor.id(), "USER", true, 1)));
        assertThatThrownBy(() -> service.delete(other, actor)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(users, never()).byId(other);
    }
}
