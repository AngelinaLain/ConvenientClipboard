package com.convenientclipboard.auth;

import java.util.UUID;

public record AppPrincipal(UUID id, String username, String role) {
}
