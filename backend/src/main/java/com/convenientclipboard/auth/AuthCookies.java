package com.convenientclipboard.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class AuthCookies {
    public static final String NAME = "clipboard_auth";
    private final boolean secure;
    public AuthCookies(@Value("${app.cookie-secure:false}") boolean secure) { this.secure = secure; }
    public Optional<String> read(HttpServletRequest request) {
        return Optional.ofNullable(request.getCookies()).stream().flatMap(Arrays::stream)
                .filter(c -> NAME.equals(c.getName())).map(Cookie::getValue).findFirst();
    }
    public void set(HttpServletRequest request, HttpServletResponse response, String value, Duration duration) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(NAME, value).httpOnly(true)
                .secure(secure || request.isSecure()).sameSite("Lax").path("/api").maxAge(duration).build().toString());
    }
    public void clear(HttpServletRequest request, HttpServletResponse response) {
        set(request, response, "", Duration.ZERO);
    }
}
