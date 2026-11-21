package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/student/profile")
@PreAuthorize("hasRole('STUDENT')")
public class StudentProfileController {
    private final JdbcTemplate jdbc;

    public StudentProfileController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> get(Authentication auth) {
        var rows = jdbc.queryForList(
            "select profile_image from students where tenant_id=? and user_id=? and active",
            TenantContext.require(), auth.getName());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student profile not found.");
        return Map.of("profileImage", rows.get(0).get("profile_image") == null ? "" : rows.get(0).get("profile_image"));
    }

    @PutMapping
    public Map<String, Object> save(@Valid @RequestBody ProfileImageRequest request, Authentication auth) {
        int updated = jdbc.update(
            "update students set profile_image=? where tenant_id=? and user_id=? and active",
            request.profileImage(), TenantContext.require(), auth.getName());
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student profile not found.");
        return Map.of("profileImage", request.profileImage());
    }

    public record ProfileImageRequest(@NotNull @Size(max=2_800_000) String profileImage) {}
}
