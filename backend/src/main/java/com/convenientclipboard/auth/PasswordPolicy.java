package com.convenientclipboard.auth;

import com.convenientclipboard.common.ApiException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;

public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static void validate(String password) {
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码至少 8 位，且 UTF-8 编码不能超过 72 字节");
    }
}
