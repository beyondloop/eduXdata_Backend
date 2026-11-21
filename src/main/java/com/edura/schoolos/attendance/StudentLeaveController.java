package com.edura.schoolos.attendance;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/student-leave")
public class StudentLeaveController {
    private final JdbcTemplate jdbc;
    public StudentLeaveController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public static final String SELECT = "select l.*, s.display_name as student_name, s.class_name, s.section_name from student_leave l join students s on s.id = l.student_id and s.tenant_id = l.tenant_id where l.tenant_id = ?";

    @GetMapping
    @PreAuthorize("hasAnyRole('STUDENT','TEACHER')")
    public List<Map<String,Object>> list(Authentication auth) {
        boolean student = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_STUDENT"));
        return jdbc.queryForList(SELECT + (student ? " and s.user_id = ?" : " and l.teacher_user_id = ?") + " order by l.submitted_at desc", TenantContext.require(), auth.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('STUDENT')")
    @Transactional
    public Map<String,Object> apply(@Valid @RequestBody Application entry, Authentication auth) {
        if (entry.toDate().isBefore(entry.fromDate()) || entry.toDate().isAfter(entry.fromDate().plusDays(365)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid leave period of at most 366 days");
        var tenant = TenantContext.require();
        var students = jdbc.queryForList("select id from students where tenant_id = ? and user_id = ? and active = true for update", tenant, auth.getName());
        if (students.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student profile not found");
        var student = students.get(0).get("id");
        var teachers = jdbc.queryForList("""
            select distinct w.teacher_user_id from teacher_workloads w join students s on s.tenant_id = w.tenant_id
            where s.id = ? and s.tenant_id = ? and w.status = 'ACTIVE'
            and lower(trim(w.teacher_role)) = 'class teacher' and w.teacher_user_id is not null
            and lower(trim(w.class_name)) = lower(trim(regexp_replace(coalesce(s.class_name,''), '^class\\s*', '', 'i')))
            and lower(trim(w.section_name)) = lower(trim(coalesce(s.section_name,'')))
            """, student, tenant);
        if (teachers.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Please ask the school to assign one class teacher before applying for leave");
        Integer overlaps = jdbc.queryForObject("select count(*) from student_leave where tenant_id = ? and student_id = ? and status in ('PENDING','APPROVED') and from_date <= ? and to_date >= ?", Integer.class, tenant, student, entry.toDate(), entry.fromDate());
        if (overlaps != null && overlaps > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have pending or approved leave for these dates");
        var id = UUID.randomUUID();
        jdbc.update("insert into student_leave(id, tenant_id, student_id, teacher_user_id, leave_type, from_date, to_date, comments) values (?, ?, ?, ?, ?, ?, ?, ?)", id, tenant, student, teachers.get(0).get("teacher_user_id"), entry.leaveType(), entry.fromDate(), entry.toDate(), entry.comments().trim());
        return Map.of("id", id, "status", "PENDING");
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public Map<String,Object> decide(@PathVariable UUID id, @Valid @RequestBody Decision decision, Authentication auth) {
        var tenant = TenantContext.require();
        var rows = jdbc.queryForList("select * from student_leave where id = ? and tenant_id = ? and teacher_user_id = ? and status = 'PENDING' for update", id, tenant, auth.getName());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This request is unavailable or has already been reviewed");
        var row = rows.get(0);
        String status = decision.approve() ? "APPROVED" : "REJECTED";
        jdbc.update("update student_leave set status = ?, reviewed_at = now(), reviewed_by = ? where id = ? and tenant_id = ?", status, auth.getName(), id, tenant);
        if (decision.approve()) {
            var end = LocalDate.parse(row.get("to_date").toString());
            for (var date = LocalDate.parse(row.get("from_date").toString()); !date.isAfter(end); date = date.plusDays(1)) {
                jdbc.update("insert into attendance(id, tenant_id, student_id, attendance_date, status) values (?, ?, ?, ?, 'ABSENT') on conflict (tenant_id, student_id, attendance_date) do update set status = 'ABSENT'", UUID.randomUUID(), tenant, row.get("student_id"), date);
            }
        }
        return Map.of("id", id, "status", status);
    }
    public record Application(@NotNull @Pattern(regexp="SICK|CASUAL|EMERGENCY|OTHER") String leaveType, @NotNull LocalDate fromDate, @NotNull LocalDate toDate, @NotBlank @Size(max=1000) String comments) {}
    public record Decision(@NotNull Boolean approve) {}
}
