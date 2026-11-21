package com.edura.schoolos.auth;

import com.edura.schoolos.security.JwtTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final JwtTokenService tokens;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public AuthController(JwtTokenService tokens, JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        if (request.username().isBlank() || request.password().isBlank()) return ResponseEntity.badRequest().build();
        UUID tenantId = UUID.fromString(request.tenantId() == null || request.tenantId().isBlank() ? "11111111-1111-1111-1111-111111111111" : request.tenantId());
        String role = request.role() == null || request.role().isBlank() ? "ADMIN" : request.role().toUpperCase();
        var users = jdbc.query("""
            select user_id, password_hash, 'SUPER_ADMIN' role, display_name, null::varchar class_name, null::varchar section_name, null::varchar roll_number from super_admins where tenant_id = ? and (lower(email) = lower(?) or lower(user_id) = lower(?)) and active
            union all select user_id, password_hash, 'ADMIN', display_name, null::varchar, null::varchar, null::varchar from admins where tenant_id = ? and (lower(email) = lower(?) or lower(user_id) = lower(?)) and active
            union all select user_id, password_hash, 'SUB_ADMIN', display_name, null::varchar, null::varchar, null::varchar from sub_admins where tenant_id = ? and (lower(email) = lower(?) or lower(user_id) = lower(?)) and active
            union all select user_id, password_hash, 'TEACHER', display_name, null::varchar, null::varchar, null::varchar from teachers where tenant_id = ? and (lower(email) = lower(?) or lower(user_id) = lower(?)) and active
            union all select user_id, password_hash, 'STUDENT', display_name, class_name, section_name, roll_number from students where tenant_id = ? and (lower(email) = lower(?) or lower(user_id) = lower(?)) and active and password_hash is not null
            """, (rs, rowNum) -> new UserRecord(rs.getString("user_id"), rs.getString("password_hash"), rs.getString("role"), rs.getString("display_name"), rs.getString("class_name"), rs.getString("section_name"), rs.getString("roll_number")), tenantId, request.username(), request.username(), tenantId, request.username(), request.username(), tenantId, request.username(), request.username(), tenantId, request.username(), request.username(), tenantId, request.username(), request.username());
        if (!users.isEmpty()) {
            var user = users.get(0);
            if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
                return unauthorized("Incorrect user ID/email or password");
            }
            role = user.role();
            return ResponseEntity.ok(new LoginResponse(tokens.issue(user.userCode(), tenantId, role), user.userCode(), role, tenantId.toString(), user.displayName(), user.className(), user.sectionName(), user.rollNumber()));
        } else {
            return unauthorized("No active account was found for that user ID or email");
        }
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        UUID tenantId = UUID.fromString(request.tenantId() == null || request.tenantId().isBlank() ? "11111111-1111-1111-1111-111111111111" : request.tenantId());
        String role = request.role() == null || request.role().isBlank() ? "STUDENT" : request.role().toUpperCase();
        String registrationType = request.registrationType() == null || request.registrationType().isBlank() ? "OLD" : request.registrationType().toUpperCase();
        if (!Set.of("SUPER_ADMIN", "ADMIN", "SUB_ADMIN", "TEACHER", "STUDENT").contains(role)) return ResponseEntity.badRequest().body(Map.of("message", "Unsupported role"));
        if (!Set.of("OLD", "NEW").contains(registrationType)) return ResponseEntity.badRequest().body(Map.of("message", "Registration type must be Old or New"));
        var exists = jdbc.queryForObject("select count(*) from (select email from super_admins where tenant_id = ? union all select email from admins where tenant_id = ? union all select email from sub_admins where tenant_id = ? union all select email from teachers where tenant_id = ? union all select email from students where tenant_id = ?) a where lower(email) = lower(?)", Long.class, tenantId, tenantId, tenantId, tenantId, tenantId, request.email());
        if (exists != null && exists > 0) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "An account with this email already exists"));
        UUID id = UUID.randomUUID();
        String userCode = nextUserCode(tenantId, role);
        if ("STUDENT".equals(role) && (request.className() == null || request.className().isBlank() || request.sectionName() == null || request.sectionName().isBlank() || request.rollNumber() == null || request.rollNumber().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Class, section, and roll number are required for student registration"));
        }
        if ("STUDENT".equals(role)) {
            Boolean duplicateStudent = jdbc.queryForObject("""
                select exists(select 1 from students where tenant_id = ? and active = true and
                    lower(trim(coalesce(roll_number, ''))) = lower(trim(?))
                    and lower(trim(coalesce(class_name, ''))) = lower(trim(?))
                    and lower(trim(coalesce(section_name, ''))) = lower(trim(?)))
                """, Boolean.class, tenantId, request.rollNumber(), request.className(), request.sectionName());
            if (Boolean.TRUE.equals(duplicateStudent)) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "A student with this roll number is already registered in this class and section"));
        }
        if ("TEACHER".equals(role) && (request.teacherId() == null || request.teacherId().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Teacher ID is required for teacher registration"));
        }
        String passwordHash = passwordEncoder.encode(request.password());
        if ("STUDENT".equals(role)) {
            UUID classId = jdbc.query("select id from classes where tenant_id = ? and name = ? limit 1", rs -> rs.next() ? rs.getObject("id", UUID.class) : null, tenantId, request.className());
            if (classId == null) {
                classId = UUID.randomUUID();
                jdbc.update("insert into classes(id, tenant_id, name) values (?, ?, ?)", classId, tenantId, request.className());
            }
            UUID sectionId = jdbc.query("select id from sections where tenant_id = ? and class_id = ? and name = ? limit 1", rs -> rs.next() ? rs.getObject("id", UUID.class) : null, tenantId, classId, request.sectionName());
            if (sectionId == null) {
                sectionId = UUID.randomUUID();
                jdbc.update("insert into sections(id, tenant_id, class_id, name) values (?, ?, ?, ?)", sectionId, tenantId, classId, request.sectionName());
            }
            jdbc.update("insert into students(id, tenant_id, admission_number, display_name, email, password_hash, user_id, roll_number, class_name, section_name, section_id, registration_type) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, tenantId, userCode, request.displayName(), request.email(), passwordHash, userCode, request.rollNumber(), request.className(), request.sectionName(), sectionId, registrationType);
        } else if ("TEACHER".equals(role)) {
            jdbc.update("insert into teachers(id, tenant_id, user_id, teacher_id, email, password_hash, display_name, registration_type) values (?, ?, ?, ?, ?, ?, ?, ?)", id, tenantId, userCode, request.teacherId(), request.email(), passwordHash, request.displayName(), registrationType);
        } else {
            String table = switch (role) { case "SUPER_ADMIN" -> "super_admins"; case "SUB_ADMIN" -> "sub_admins"; default -> "admins"; };
            jdbc.update("insert into " + table + "(id, tenant_id, user_id, email, password_hash, display_name) values (?, ?, ?, ?, ?, ?)", id, tenantId, userCode, request.email(), passwordHash, request.displayName());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "userId", userCode, "registrationType", registrationType, "message", "Account created successfully"));
    }

    private String nextUserCode(UUID tenantId, String role) {
        String prefix = switch (role) { case "SUPER_ADMIN", "SUB_ADMIN" -> "SA"; case "ADMIN" -> "AD"; case "TEACHER" -> "TH"; default -> "ST"; };
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = prefix + String.format("%06d", random.nextInt(1_000_000));
            Long count = jdbc.queryForObject("select count(*) from (select user_id from super_admins where tenant_id = ? union all select user_id from admins where tenant_id = ? union all select user_id from sub_admins where tenant_id = ? union all select user_id from teachers where tenant_id = ? union all select user_id from students where tenant_id = ?) a where user_id = ?", Long.class, tenantId, tenantId, tenantId, tenantId, tenantId, code);
            if (count == null || count == 0) return code;
        }
        throw new IllegalStateException("Could not generate a unique user ID");
    }

    private ResponseEntity<LoginResponse> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header("X-Auth-Error", message)
            .build();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password, String tenantId, String role) {}
    public record RegisterRequest(@NotBlank String email, @Size(min = 6) String password, @NotBlank String displayName, String tenantId, String role, String className, String sectionName, String rollNumber, String teacherId, String registrationType) {}
    public record LoginResponse(String accessToken, String userId, String role, String tenantId, String displayName, String className, String sectionName, String rollNumber) {}
    private record UserRecord(String userCode, String passwordHash, String role, String displayName, String className, String sectionName, String rollNumber) {}
}
