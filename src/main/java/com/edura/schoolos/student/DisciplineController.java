package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/discipline")
public class DisciplineController {
    private static final List<DisciplineType> TYPES = List.of(
        new DisciplineType("Mobile use", 20),
        new DisciplineType("Late arrival", 5),
        new DisciplineType("Uniform violation", 5),
        new DisciplineType("Littering", 5),
        new DisciplineType("Class disruption", 10),
        new DisciplineType("Skipping class", 15),
        new DisciplineType("Disrespectful behaviour", 15),
        new DisciplineType("Cheating in an examination", 25),
        new DisciplineType("Damage to school property", 25),
        new DisciplineType("Bullying", 30),
        new DisciplineType("Physical fighting", 30)
    );
    private final JdbcTemplate jdbc;
    private final StudentController students;
    public DisciplineController(JdbcTemplate jdbc, StudentController students) { this.jdbc = jdbc; this.students = students; }

    @GetMapping("/types")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER','STUDENT')")
    public List<DisciplineType> types() { return TYPES; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER','STUDENT')")
    public List<Map<String, Object>> list(Authentication auth) {
        List<Map<String, Object>> visible = isStudent(auth)
            ? jdbc.queryForList("select id, display_name, user_id, class_name, section_name from students where tenant_id = ? and user_id = ? and active", TenantContext.require(), auth.getName())
            : students.list("", auth);
        return visible.stream().map(student -> {
            var result = new HashMap<>(student);
            var entries = jdbc.queryForList("select * from student_discipline where tenant_id = ? and student_id = ? order by incident_date desc, created_at desc", TenantContext.require(), student.get("id"));
            long total = entries.stream().filter(e -> "APPROVED".equals(e.get("status"))).mapToLong(e -> ((Number)e.get("points")).longValue()).sum();
            result.put("entries", entries); result.put("total_points", total); result.put("tc_eligible", total >= 100);
            return (Map<String, Object>)result;
        }).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public Map<String, Object> add(@Valid @RequestBody Entry entry, Authentication auth) {
        boolean visible = students.list("", auth).stream().anyMatch(s -> entry.studentId().equals(s.get("id")));
        if (!visible) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found in your assigned students");
        DisciplineType type = TYPES.stream().filter(t -> t.incident().equalsIgnoreCase(entry.incident().trim()))
            .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an incident from the discipline list"));
        int points = type.points();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into student_discipline(id, tenant_id, student_id, incident, points, incident_date, notes, recorded_by, status) values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')",
            id, TenantContext.require(), entry.studentId(), type.incident(), points, entry.incidentDate(), entry.notes() == null ? "" : entry.notes().trim(), auth.getName());
        return Map.of("id", id, "points", points, "status", "PENDING");
    }
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    @Transactional
    public List<Map<String, Object>> submit(@Valid @RequestBody Submission submission, Authentication auth) {
        var chosen = submission.incidents().stream().map(name -> TYPES.stream()
            .filter(t -> t.incident().equalsIgnoreCase(name.trim())).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select incidents from the discipline list")))
            .distinct().toList();
        return chosen.stream().map(type -> add(new Entry(submission.studentId(), type.incident(), null,
            submission.incidentDate(), submission.notes()), auth)).toList();
    }

    @GetMapping("/requests")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> requests() {
        return jdbc.queryForList("""
            select d.*, s.display_name, s.user_id from student_discipline d
            join students s on s.id = d.student_id and s.tenant_id = d.tenant_id
            where d.tenant_id = ? and d.status = 'PENDING' order by d.created_at
            """, TenantContext.require());
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> decide(@PathVariable UUID id, @Valid @RequestBody Decision decision, Authentication auth) {
        String status = decision.approve() ? "APPROVED" : "REJECTED";
        int updated = jdbc.update("""
            update student_discipline set status = ?, reviewed_by = ?, reviewed_at = now()
            where id = ? and tenant_id = ? and status = 'PENDING'
            """, status, auth.getName(), id, TenantContext.require());
        if (updated == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Request not found or already reviewed");
        return Map.of("status", status);
    }

    public record Submission(@NotNull UUID studentId, @NotEmpty @Size(max=11) List<@NotBlank String> incidents,
        @NotNull @PastOrPresent LocalDate incidentDate, @Size(max=2000) String notes) {}
    public record Decision(@NotNull Boolean approve) {}
    private boolean isStudent(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_STUDENT")); }
    public record DisciplineType(String incident, int points) {}
    public record Entry(@NotNull UUID studentId, @NotBlank @Size(max=160) String incident,
        Integer points, @NotNull @PastOrPresent LocalDate incidentDate, @Size(max=2000) String notes) {}
}
