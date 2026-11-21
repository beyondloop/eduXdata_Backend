package com.edura.schoolos.attendance;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/v1/teacher-attendance")
public class TeacherAttendanceController {
    private final JdbcTemplate jdbc;
    public TeacherAttendanceController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Kolkata");

    @GetMapping("/approvers")
    @PreAuthorize("hasRole('TEACHER')")
    public List<Map<String, Object>> approvers() {
        return jdbc.queryForList("select id, display_name, user_id from admins where tenant_id = ? and active order by display_name", TenantContext.require());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public List<Map<String, Object>> list(@RequestParam LocalDate from, @RequestParam LocalDate to, Authentication auth) {
        if (to.isBefore(from) || to.isAfter(from.plusYears(1))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range");
        String sql = "select a.*, t.display_name as teacher_name from teacher_attendance a join teachers t on t.id = a.teacher_id where a.tenant_id = ? and a.attendance_date between ? and ?";
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) return jdbc.queryForList(sql + " order by t.display_name, a.attendance_date", TenantContext.require(), from, to);
        return jdbc.queryForList(sql + " and t.user_id = ? order by a.attendance_date", TenantContext.require(), from, to, auth.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEACHER')")
    public Map<String, Object> submit(@Valid @RequestBody Entry entry, Authentication auth) {
        LocalDate today = LocalDate.now(SCHOOL_ZONE);
        if (entry.date().isAfter(today)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Future attendance is not allowed");
        UUID tenant = TenantContext.require();
        var teachers = jdbc.queryForList("select id from teachers where tenant_id = ? and user_id = ? and active", tenant, auth.getName());
        if (teachers.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active teacher account required");
        boolean late = entry.date().isBefore(today.minusDays(2));
        if (late) {
            if (entry.approverId() == null || jdbc.queryForObject("select count(*) from admins where tenant_id = ? and id = ? and active", Integer.class, tenant, entry.approverId()) == 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an active admin approver for attendance older than two days");
        }
        String status = late ? "PENDING" : "PRESENT";
        int changed = jdbc.update("""
            insert into teacher_attendance(id, tenant_id, teacher_id, attendance_date, hours, status, approver_id)
            values (?, ?, ?, ?, ?, ?, ?)
            on conflict (tenant_id, teacher_id, attendance_date) do update
            set hours = excluded.hours, status = excluded.status, approver_id = excluded.approver_id, submitted_at = now(), reviewed_at = null
            where teacher_attendance.status = 'REJECTED'
            """, UUID.randomUUID(), tenant, teachers.get(0).get("id"), entry.date(), entry.hours(), status, late ? entry.approverId() : null);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Attendance already submitted for this date");
        return Map.of("status", status);
    }

    @GetMapping("/requests")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> requests(Authentication auth) {
        return jdbc.queryForList("""
            select a.*, t.display_name as teacher_name from teacher_attendance a
            join teachers t on t.id = a.teacher_id join admins ad on ad.id = a.approver_id
            where a.tenant_id = ? and ad.tenant_id = a.tenant_id and ad.user_id = ? and ad.active and a.status = 'PENDING'
            order by a.submitted_at
            """, TenantContext.require(), auth.getName());
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> decide(@PathVariable UUID id, @Valid @RequestBody Decision decision, Authentication auth) {
        String status = decision.approve() ? "PRESENT" : "REJECTED";
        int changed = jdbc.update("""
            update teacher_attendance a set status = ?, reviewed_at = now()
            where a.id = ? and a.tenant_id = ? and a.status = 'PENDING'
            and exists (select 1 from admins ad where ad.id = a.approver_id and ad.tenant_id = a.tenant_id and ad.user_id = ? and ad.active)
            """, status, id, TenantContext.require(), auth.getName());
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Pending request not found or assigned to another admin");
        return Map.of("status", status);
    }
    public record Entry(@NotNull LocalDate date, @NotNull @DecimalMin("0.01") @DecimalMax("24") @Digits(integer = 2, fraction = 2) BigDecimal hours, UUID approverId) {}
    public record Decision(@NotNull Boolean approve) {}
}
