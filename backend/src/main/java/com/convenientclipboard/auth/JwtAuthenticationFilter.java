package com.convenientclipboard.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UserRepository users;
    private final StringRedisTemplate redis;
    private final AuthCookies cookies;
    public JwtAuthenticationFilter(JwtService jwt, UserRepository users, StringRedisTemplate redis, AuthCookies cookies) {
        this.jwt = jwt; this.users = users; this.redis = redis;
        this.cookies = cookies;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var token = cookies.read(request);
        if (token.isPresent()) {
            try {
                var claims = jwt.decode(token.get());
                if (Boolean.TRUE.equals(redis.hasKey("auth:revoked:" + claims.getId()))) {
                    cookies.clear(request, response);
                } else {
                    var user = users.byId(UUID.fromString(claims.getSubject()));
                    Object versionClaim = claims.getClaim("ver");
                    long version = versionClaim == null ? 0 : versionClaim instanceof Number number ? number.longValue() : -1;
                    if (user.isPresent() && user.get().enabled() && version >= 0 && user.get().authVersion() == version) {
                        var principal = user.get().principal();
                        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } else cookies.clear(request, response);
                }
            } catch (JwtException | IllegalArgumentException ex) {
                cookies.clear(request, response);
            } catch (DataAccessException ex) {
                response.setStatus(503); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"认证服务暂时不可用\"}"); return;
            }
        }
        chain.doFilter(request, response);
    }
}
