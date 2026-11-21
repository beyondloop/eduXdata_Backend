package com.edura.schoolos.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class PortalPermissionController {
    private static final Set<String> ROLES = Set.of("ADMIN", "SUB_ADMIN", "TEACHER", "STUDENT");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PortalPermissionController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/admin/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PermissionAssignment> assignments() {
        return jdbc.query("select target_role, target_user_id, features::text from portal_permissions where tenant_id = ? order by target_role, target_user_id",
            (rs, row) -> new PermissionAssignment(rs.getString("target_role"), rs.getString("target_user_id"), parseFeatures(rs.getString("features"))), TenantContext.require());
    }

    @GetMapping("/admin/permissions/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PermissionUser> users() {
        return jdbc.query("""
            select 'ADMIN' as role, user_id as "userId", display_name as "displayName", email from admins where tenant_id = ? and active = true
            union all
            select 'SUB_ADMIN' as role, user_id as "userId", display_name as "displayName", email from sub_admins where tenant_id = ? and active = true
            union all
            select 'TEACHER' as role, user_id as "userId", display_name as "displayName", email from teachers where tenant_id = ? and active = true
            union all
            select 'STUDENT' as role, user_id as "userId", display_name as "displayName", email from students where tenant_id = ? and active = true
            order by role, "displayName"
            """, (rs, row) -> new PermissionUser(rs.getString("role"), rs.getString("userId"), rs.getString("displayName"), rs.getString("email")),
            TenantContext.require(), TenantContext.require(), TenantContext.require(), TenantContext.require());
    }

    @PutMapping("/admin/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    public PermissionAssignment save(@RequestBody PermissionAssignment assignment, Authentication authentication) {
        String role = normalizeRole(assignment.role());
        String userId = assignment.userId() == null ? "" : assignment.userId().trim();
        if (userId.isBlank() || userId.length() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid user ID");
        String accountTable = switch (role) { case "ADMIN" -> "admins"; case "SUB_ADMIN" -> "sub_admins"; case "TEACHER" -> "teachers"; default -> "students"; };
        Integer accountCount = jdbc.queryForObject("select count(*) from " + accountTable + " where tenant_id = ? and user_id = ? and active = true", Integer.class, TenantContext.require(), userId);
        if (accountCount == null || accountCount == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The selected account is not active in this school");
        List<String> features = assignment.features() == null ? List.of() : assignment.features().stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        String serialized;
        try { serialized = mapper.writeValueAsString(features); }
        catch (JsonProcessingException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid permission list"); }
        jdbc.update("""
            insert into portal_permissions (tenant_id, target_role, target_user_id, features, updated_by)
            values (?, ?, ?, ?::jsonb, ?)
            on conflict (tenant_id, target_role, target_user_id)
            do update set features = excluded.features, updated_by = excluded.updated_by, updated_at = now()
            """, TenantContext.require(), role, userId, serialized, authentication.getName());
        return new PermissionAssignment(role, userId, features);
    }

    @GetMapping("/permissions/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN', 'TEACHER', 'STUDENT')")
    public CurrentPermissions current(Authentication authentication) {
        String role = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .filter(value -> value.startsWith("ROLE_")).map(value -> value.substring(5).toUpperCase(Locale.ROOT))
            .filter(ROLES::contains).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Portal role is unavailable"));
        List<List<String>> rows = jdbc.query("select features::text from portal_permissions where tenant_id = ? and target_role = ? and target_user_id = ?",
            (rs, row) -> parseFeatures(rs.getString("features")), TenantContext.require(), role, authentication.getName());
        return rows.isEmpty() ? new CurrentPermissions(false, List.of()) : new CurrentPermissions(true, rows.get(0));
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose Admin, SubAdmin, Teacher or Student");
        return normalized;
    }

    private List<String> parseFeatures(String value) {
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException error) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Saved permissions could not be read"); }
    }

    public record PermissionAssignment(String role, String userId, List<String> features) {}
    public record PermissionUser(String role, String userId, String displayName, String email) {}
    public record CurrentPermissions(boolean assigned, List<String> features) {}
}
