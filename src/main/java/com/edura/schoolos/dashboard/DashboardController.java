package com.edura.schoolos.dashboard;

import com.edura.schoolos.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final JdbcTemplate jdbc;

    public DashboardController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN','TEACHER','STUDENT','PARENT')")
    public Map<String, Object> summary() {
        var tenant = TenantContext.require();
        var students = jdbc.queryForObject("select count(*) from students where tenant_id = ? and active = true", Long.class, tenant);
        var attendance = jdbc.queryForObject("select count(*) from attendance where tenant_id = ? and attendance_date = ? and status = 'PRESENT'", Long.class, tenant, LocalDate.now());
        var marked = jdbc.queryForObject("select count(*) from attendance where tenant_id = ? and attendance_date = ?", Long.class, tenant, LocalDate.now());
        var pendingFees = jdbc.queryForObject("select coalesce(sum(amount), 0) from invoices where tenant_id = ? and status = 'PENDING'", java.math.BigDecimal.class, tenant);
        return Map.of("students", students == null ? 0 : students, "presentToday", attendance == null ? 0 : attendance,
            "markedToday", marked == null ? 0 : marked, "pendingFees", pendingFees == null ? 0 : pendingFees);
    }
}
