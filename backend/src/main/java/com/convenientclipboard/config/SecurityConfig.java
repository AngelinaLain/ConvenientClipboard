package com.convenientclipboard.config;

import com.convenientclipboard.auth.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfException;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwt,
                                           @Value("${app.cookie-secure:false}") boolean cookieSecure) throws Exception {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("clipboard_csrf");
        repository.setCookieCustomizer(c -> {
            c.sameSite("Lax").path("/");
            if (cookieSecure) c.secure(true);
        });
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.csrfTokenRepository(repository).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/error", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/public/**", "/api/shares/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register", "/api/files").permitAll()
                        .requestMatchers("/api/**").authenticated().anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}");
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                            if (ex instanceof CsrfException) {
                                res.getWriter().write("{\"code\":\"CSRF_INVALID\",\"message\":\"请求校验已更新，请重试\"}");
                            } else {
                                res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"没有操作权限\"}");
                            }
                        }))
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
