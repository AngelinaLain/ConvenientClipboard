package com.convenientclipboard.admin;

import com.convenientclipboard.auth.AppPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {
    private final UserAdminService service;
    public UserAdminController(UserAdminService service) { this.service = service; }
    public record CreateRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}") String username,
                                @NotBlank @Size(min = 8, max = 72) String password,
                                @NotBlank @Pattern(regexp = "USER|ADMIN") String role) {}
    public record UpdateRequest(@NotBlank @Pattern(regexp = "USER|ADMIN") String role, @NotNull Boolean enabled) {}
    public record PasswordRequest(@NotBlank @Size(min = 8, max = 72) String password) {}
    @GetMapping
    public UserAdminService.UserPage list(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(defaultValue = "") String query) {
        return service.list(page, size, query);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserAdminService.UserInfo create(@Valid @RequestBody CreateRequest request, @AuthenticationPrincipal AppPrincipal actor) {
        return service.create(request.username(), request.password(), request.role(), actor);
    }
    @PatchMapping("/{id}")
    public UserAdminService.UserInfo update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request,
                                          @AuthenticationPrincipal AppPrincipal actor) {
        return service.update(id, request.role(), request.enabled(), actor);
    }
    @PostMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id, @Valid @RequestBody PasswordRequest request,
                                             @AuthenticationPrincipal AppPrincipal actor) {
        service.resetPassword(id, request.password(), actor);
        return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal AppPrincipal actor) {
        service.delete(id, actor);
        return ResponseEntity.noContent().build();
    }
}
