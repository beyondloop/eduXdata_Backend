package com.edura.schoolos.notification;

import com.edura.schoolos.security.TenantContext;
import com.edura.schoolos.attendance.LeavePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final JdbcTemplate jdbc;
    public NotificationController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping @PreAuthorize("hasAnyRole('ADMIN','TEACHER','STUDENT','PARENT')")
    public List<Map<String, Object>> list(Authentication auth) {
        UUID tenant = TenantContext.require();
        var result = new ArrayList<>(jdbc.queryForList("select id, title, body, audience, published_at from notifications where tenant_id = ? order by published_at desc", tenant));
        boolean teacher = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_TEACHER"));
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (admin) {
            var pending = jdbc.queryForList("""
                select d.id, d.student_id, d.incident, d.points, d.created_at, s.display_name, s.user_id
                from student_discipline d join students s on s.id = d.student_id and s.tenant_id = d.tenant_id
                where d.tenant_id = ? and d.status = 'PENDING' order by d.created_at desc
                """, tenant);
            for (var entry : pending) {
                result.add(Map.of("id", "discipline-" + entry.get("id"),
                    "title", "Discipline points awaiting approval",
                    "body", entry.get("display_name") + " (" + entry.get("user_id") + "): " + entry.get("incident") + " - " + entry.get("points") + " points. Review and approve or reject this incident.",
                    "audience", "ADMIN", "published_at", entry.get("created_at"), "source", "DISCIPLINE",
                    "destination", "Discipline Points", "student_id", entry.get("student_id")));
            }
        }
        if (teacher || admin) {
            String sql = "select l.id, l.teacher_id, l.leave_type, l.from_date, l.to_date, l.status, l.submitted_at, l.reviewed_at, t.display_name as teacher_name, ad.display_name as approver_name from teacher_leave l join teachers t on t.id = l.teacher_id and t.tenant_id = l.tenant_id left join admins ad on ad.id = l.reviewed_by and ad.tenant_id = l.tenant_id where l.tenant_id = ?";
            var leaves = teacher ? jdbc.queryForList(sql + " and t.user_id = ?", tenant, auth.getName())
                : jdbc.queryForList(sql, tenant);
            for (var leave : LeavePolicy.withPayBreakdown(leaves)) {
                if (admin && !"PENDING".equals(leave.get("status"))) continue;
                String pay = "REJECTED".equals(leave.get("status")) ? " No leave balance or pay impact." : " " + leave.get("paid_days") + " paid days; " + leave.get("unpaid_days") + " days leave without pay" + ("PENDING".equals(leave.get("status")) ? " if approved." : ".");
                String type = switch (leave.get("leave_type").toString()) {
                    case "SICK" -> "Sick leave";
                    case "EMERGENCY" -> "Emergency leave";
                    default -> "Casual leave";
                };
                String dates = leave.get("from_date") + (leave.get("from_date").equals(leave.get("to_date")) ? "" : " to " + leave.get("to_date"));
                result.add(Map.of("id", "leave-submitted-" + leave.get("id"), "title", teacher ? "Leave application submitted" : "Leave request awaiting approval",
                    "body", (teacher ? "Your " : leave.get("teacher_name") + " requested ") + type.toLowerCase() + " for " + dates + pay + (teacher ? " Track its status in My Attendance." : " Review it in Leave Approvals."),
                    "audience", teacher ? "TEACHER" : "ADMIN", "published_at", leave.get("submitted_at"), "source", "LEAVE", "destination", teacher ? "My Attendance" : "Leave Approvals"));
                if (teacher && leave.get("reviewed_at") != null && !"PENDING".equals(leave.get("status"))) {
                    String status = leave.get("status").toString().toLowerCase();
                    result.add(Map.of("id", "leave-decision-" + leave.get("id"), "title", "Leave " + status,
                        "body", "Your " + type.toLowerCase() + " for " + dates + " was " + status + " by " + (leave.get("approver_name") == null ? "your admin" : leave.get("approver_name")) + pay + " View details in My Attendance.",
                        "audience", "TEACHER", "published_at", leave.get("reviewed_at"), "source", "LEAVE", "destination", "My Attendance"));
                }
            }
        }
        boolean student = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_STUDENT"));
        if (teacher || student) {
            var leaves = jdbc.queryForList(com.edura.schoolos.attendance.StudentLeaveController.SELECT
                + (teacher ? " and l.teacher_user_id = ? and l.status = 'PENDING'" : " and s.user_id = ?"), tenant, auth.getName());
            for (var leave : leaves) {
                result.add(Map.of("id", "student-leave-" + leave.get("id"),
                    "title", teacher ? "Student leave awaiting approval" : "Leave " + leave.get("status").toString().toLowerCase(),
                    "body", leave.get("student_name") + ": " + leave.get("leave_type") + " leave, " + leave.get("from_date") + " to " + leave.get("to_date") + ". " + leave.get("comments"),
                    "audience", teacher ? "TEACHER" : "STUDENT", "published_at", leave.get("reviewed_at") == null ? leave.get("submitted_at") : leave.get("reviewed_at"),
                    "source", "STUDENT_LEAVE", "destination", teacher ? "Student Leave Approvals" : "Leave Management"));
            }
        }
        result.sort(Comparator.comparing((Map<String, Object> row) -> row.get("published_at").toString()).reversed());
        return result;
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@Valid @RequestBody Notice request, @AuthenticationPrincipal Jwt jwt) { UUID id = UUID.randomUUID(); jdbc.update("insert into notifications(id, tenant_id, title, body, audience, created_by) values (?, ?, ?, ?, ?, ?)", id, TenantContext.require(), request.title().trim(), request.body().trim(), request.audience().trim(), jwt.getSubject()); return Map.of("id", id, "title", request.title().trim(), "body", request.body().trim(), "audience", request.audience().trim()); }

    @PutMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    public void update(@PathVariable UUID id, @Valid @RequestBody Notice request) { jdbc.update("update notifications set title = ?, body = ?, audience = ? where id = ? and tenant_id = ?", request.title().trim(), request.body().trim(), request.audience().trim(), id, TenantContext.require()); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) { jdbc.update("delete from notifications where id = ? and tenant_id = ?", id, TenantContext.require()); }
    public record Notice(@NotBlank String title, @NotBlank String body, @NotBlank String audience) {}
}
