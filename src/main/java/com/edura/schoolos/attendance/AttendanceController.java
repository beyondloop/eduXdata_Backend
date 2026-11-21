package com.edura.schoolos.attendance;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {
    private final JdbcTemplate jdbc;
    public AttendanceController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER','STUDENT','PARENT')")
    public List<Map<String, Object>> list(@RequestParam(required = false) LocalDate date, Authentication authentication) {
        var day = date == null ? LocalDate.now() : date;
        if (hasRole(authentication, "ROLE_STUDENT")) {
            return jdbc.queryForList("select a.id, a.student_id, a.attendance_date, a.status from attendance a join students s on s.id = a.student_id where a.tenant_id = ? and s.user_id = ? and a.attendance_date = ?", TenantContext.require(), authentication.getName(), day);
        }
        return jdbc.queryForList("select a.id, a.student_id, s.display_name, s.admission_number, a.attendance_date, a.attendance_day, a.class_name, a.section_name, a.class_teacher_user_id, a.class_teacher_name, a.status, a.marked_by from attendance a join students s on s.id = a.student_id where a.tenant_id = ? and a.attendance_date = ? order by s.display_name", TenantContext.require(), day);
    }

    /** Returns only the logged-in student's attendance for the requested calendar range. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    public List<Map<String, Object>> listMine(@AuthenticationPrincipal Jwt jwt, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        if (to.isBefore(from) || from.plusYears(1).isBefore(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a valid attendance date range");
        return jdbc.queryForList("""
            select a.attendance_date, a.status from attendance a
            join students s on s.id = a.student_id
            where a.tenant_id = ? and s.user_id = ? and a.attendance_date between ? and ?
            order by a.attendance_date
            """, TenantContext.require(), jwt.getSubject(), from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public Map<String, Object> save(@Valid @RequestBody AttendanceEntry entry, Authentication authentication) {
        var tenant = TenantContext.require();
        String status = entry.status().trim().toUpperCase();
        if (!Set.of("PRESENT", "ABSENT").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attendance status must be PRESENT or ABSENT");
        Integer studentExists = jdbc.queryForObject("select count(*) from students where id = ? and tenant_id = ? and active = true", Integer.class, entry.studentId(), tenant);
        if (studentExists == null || studentExists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found");
        if (hasRole(authentication, "ROLE_TEACHER")) requireClassTeacherAssignment(tenant, authentication.getName(), entry.studentId());
        var id = UUID.randomUUID();
        String actorTable = hasRole(authentication, "ROLE_TEACHER") ? "teachers" : "admins";
        var actors = jdbc.queryForList("select id from " + actorTable + " where tenant_id = ? and user_id = ? and active = true", tenant, authentication.getName());
        if (actors.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Attendance marker not found");
        jdbc.update("insert into attendance(id, tenant_id, student_id, attendance_date, status, marked_by) values (?, ?, ?, ?, ?, ?) on conflict (tenant_id, student_id, attendance_date) do update set status = excluded.status, marked_by = excluded.marked_by", id, tenant, entry.studentId(), entry.attendanceDate(), status, actors.get(0).get("id"));
        return Map.of("studentId", entry.studentId(), "attendanceDate", entry.attendanceDate(), "status", status);
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(authority -> role.equals(authority.getAuthority()));
    }

    private void requireClassTeacherAssignment(UUID tenant, String teacherUserId, UUID studentId) {
        Integer assigned = jdbc.queryForObject("""
            select count(*) from students s
            where s.id = ? and s.tenant_id = ? and exists (
              select 1 from teacher_workloads workload
              where workload.tenant_id = s.tenant_id and workload.teacher_user_id = ? and workload.status = 'ACTIVE'
                and lower(trim(workload.teacher_role)) = 'class teacher'
                and lower(workload.class_name) = lower(regexp_replace(coalesce(s.class_name, ''), '^class\\s*', '', 'i'))
                and lower(workload.section_name) = lower(coalesce(s.section_name, '')))
            """, Integer.class, studentId, tenant, teacherUserId);
        if (assigned == null || assigned == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can mark attendance only for your assigned class");
    }

    public record AttendanceEntry(@NotNull UUID studentId, @NotNull LocalDate attendanceDate, @NotBlank String status) {}
}
