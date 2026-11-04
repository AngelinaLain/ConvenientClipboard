package com.convenientclipboard.auth;

import com.convenientclipboard.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AppProperties properties;
    public AdminBootstrap(UserRepository users, PasswordEncoder passwords, AppProperties properties) {
        this.users = users; this.passwords = passwords; this.properties = properties;
    }
    @Override public void run(ApplicationArguments args) {
        var username = properties.adminUsername().toLowerCase(Locale.ROOT);
        if (!username.matches("[a-z0-9_]{3,32}")) throw new IllegalStateException("APP_ADMIN_USERNAME must use 3-32 letters, digits or underscores");
        var existing = users.byUsername(username);
        if (existing.isPresent()) {
            if (!"ADMIN".equals(existing.get().role())) throw new IllegalStateException("APP_ADMIN_USERNAME belongs to a non-admin account");
            return;
        }
        var password = properties.adminPassword();
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("Set APP_ADMIN_PASSWORD to a password of at least 12 characters and at most 72 UTF-8 bytes for first startup");
        users.create(username, passwords.encode(password), "ADMIN");
    }
}
