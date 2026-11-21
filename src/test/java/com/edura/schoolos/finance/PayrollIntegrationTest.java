package com.edura.schoolos.finance;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

// Opt-in: exercises PostgreSQL migrations and generated snapshots, rolling back
// every fixture. Uses the application's configured datasource.
@SpringBootTest
@Transactional(isolation = Isolation.SERIALIZABLE)
@EnabledIfEnvironmentVariable(named = "PAYROLL_INTEGRATION_TESTS", matches = "true")
class PayrollIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PayrollController controller;
    private final UUID tenant = UUID.randomUUID(), teacher = UUID.randomUUID();
    private UsernamePasswordAuthenticationToken auth(String role, String user) {
        var auth = new UsernamePasswordAuthenticationToken(user, "", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }
    @BeforeEach void setup() {
        TenantContext.set(tenant);
        jdbc.update("insert into tenants(id, name, slug) values (?, 'Payroll test school', ?)", tenant, "payroll-test-" + tenant);
        jdbc.update("insert into teachers(id, tenant_id, user_id, teacher_id, email, password_hash, display_name, pf_number) values (?, ?, 'THPAY', 'EMP-PAY', 'payroll@example.invalid', 'unused', 'Payroll Test Teacher', 'PF-TEST')", teacher, tenant);
        auth("ADMIN", "ADPAY");
    }
    @AfterEach void cleanup() { TenantContext.clear(); SecurityContextHolder.clearContext(); }
    @Test void generatesScopedImmutableMonthlySlipWithAttendanceDays() {
        controller.createPayroll(new PayrollController.PayrollRequest(teacher, "PF-TEST", new PayrollController.Structure(new BigDecimal("30000"), new BigDecimal("6000"), new BigDecimal("1800"), new BigDecimal("200"))));
        jdbc.update("insert into teacher_leave(id, tenant_id, teacher_id, leave_type, from_date, to_date, status) values (?, ?, ?, 'SICK', '2025-09-01', '2025-09-07', 'APPROVED')", UUID.randomUUID(), tenant, teacher);
        for (int day = 1; day <= 28; day++) {
            jdbc.update("insert into teacher_attendance(id, tenant_id, teacher_id, attendance_date, hours, status) values (?, ?, ?, ?, 8, 'PRESENT')",
                UUID.randomUUID(), tenant, teacher, java.time.LocalDate.of(2025, 9, day));
        }
        jdbc.update("insert into teacher_attendance(id, tenant_id, teacher_id, attendance_date, hours, status) values (?, ?, ?, '2025-08-31', 8, 'PRESENT'), (?, ?, ?, '2025-09-29', 8, 'PENDING')",
            UUID.randomUUID(), tenant, teacher, UUID.randomUUID(), tenant, teacher);
        var result = controller.generate(new PayrollController.Generate("2025-09"), auth("ADMIN", "ADPAY"));
        assertEquals(1, result.get("generated"));
        var slips = controller.slips(auth("TEACHER", "THPAY"));
        assertEquals(1, slips.size());
        var slip = slips.get(0);
        assertEquals(30, slip.get("total_days")); assertEquals(28, slip.get("paid_days")); assertEquals(2, slip.get("lwp_days"));
        assertEquals(new BigDecimal("2000.00"), slip.get("lwp_deduction"));
        assertEquals(new BigDecimal("32000.00"), slip.get("net_salary"));
        assertEquals("PF-TEST", slip.get("pf_number"));
        assertTrue(controller.slips(auth("TEACHER", "OTHER_TEACHER")).isEmpty());
        auth("ADMIN", "ADPAY");
        controller.updatePayroll(teacher, new PayrollController.PayrollRequest(teacher, "PF-TEST", new PayrollController.Structure(new BigDecimal("40000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertEquals(0, controller.generate(new PayrollController.Generate("2025-09"), auth("ADMIN", "ADPAY")).get("generated"));
        assertEquals(new BigDecimal("32000.00"), controller.slips(auth("TEACHER", "THPAY")).get(0).get("net_salary"));
        TenantContext.set(UUID.randomUUID());
        assertTrue(controller.slips(auth("ADMIN", "ADPAY")).isEmpty());
    }
    @Test void approvedCasualAndSickLeaveUseSeparateYearlyAllowancesInSalarySlip() {
        controller.createPayroll(new PayrollController.PayrollRequest(teacher, "", new PayrollController.Structure(new BigDecimal("30000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        jdbc.update("insert into teacher_leave(id, tenant_id, teacher_id, leave_type, from_date, to_date, status, submitted_at) values (?, ?, ?, 'CASUAL', '2025-01-01', '2025-01-04', 'APPROVED', '2025-01-01'), (?, ?, ?, 'CASUAL', '2025-09-01', '2025-09-03', 'APPROVED', '2025-08-01'), (?, ?, ?, 'SICK', '2025-09-04', '2025-09-09', 'APPROVED', '2025-08-02')",
            UUID.randomUUID(), tenant, teacher, UUID.randomUUID(), tenant, teacher, UUID.randomUUID(), tenant, teacher);
        for (int day = 10; day <= 30; day++)
            jdbc.update("insert into teacher_attendance(id, tenant_id, teacher_id, attendance_date, hours, status) values (?, ?, ?, ?, 8, 'PRESENT')", UUID.randomUUID(), tenant, teacher, java.time.LocalDate.of(2025, 9, day));
        var days = controller.days("2025-09").get(0);
        assertEquals(6, days.get("paid_leave_days"));
        assertEquals(3, days.get("leave_lwp_days"));
        assertEquals(27, days.get("paid_days"));
        controller.generate(new PayrollController.Generate("2025-09"), auth("ADMIN", "ADPAY"));
        var slip = controller.slips(auth("TEACHER", "THPAY")).get(0);
        assertEquals(27, slip.get("paid_days"));
        assertEquals(3, slip.get("lwp_days"));
        assertEquals(new BigDecimal("27000.00"), slip.get("net_salary"));
    }
    @Test void approvedEmergencyAddsOnlyDistinctDaysInSalaryMonth() {
        controller.createPayroll(new PayrollController.PayrollRequest(teacher, "", new PayrollController.Structure(new BigDecimal("30000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        jdbc.update("insert into teacher_attendance(id, tenant_id, teacher_id, attendance_date, hours, status) values (?, ?, ?, '2025-09-01', 8, 'PRESENT')", UUID.randomUUID(), tenant, teacher);
        UUID leave = UUID.randomUUID();
        jdbc.update("insert into teacher_leave(id, tenant_id, teacher_id, leave_type, from_date, to_date, status) values (?, ?, ?, 'EMERGENCY', '2025-08-31', '2025-09-03', 'PENDING')", leave, tenant, teacher);
        assertEquals(1L, ((Number) controller.days("2025-09").get(0).get("paid_days")).longValue());
        jdbc.update("update teacher_leave set status = 'APPROVED' where id = ?", leave);
        var days = controller.days("2025-09").get(0);
        assertEquals(2L, ((Number) days.get("emergency_days")).longValue());
        assertEquals(3L, ((Number) days.get("paid_days")).longValue());
        controller.generate(new PayrollController.Generate("2025-09"), auth("ADMIN", "ADPAY"));
        assertEquals(new BigDecimal("3000.00"), controller.slips(auth("TEACHER", "THPAY")).get(0).get("net_salary"));
    }
    @Test void removedSlipsDisappearAndCanBeRegenerated() {
        controller.createPayroll(new PayrollController.PayrollRequest(teacher, "", new PayrollController.Structure(new BigDecimal("30000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        controller.generate(new PayrollController.Generate("2025-09"), auth("ADMIN", "ADPAY"));
        controller.generate(new PayrollController.Generate("2025-08"), auth("ADMIN", "ADPAY"));
        UUID slip = (UUID) controller.slips(auth("TEACHER", "THPAY")).get(0).get("id");
        auth("ADMIN", "ADPAY");
        controller.deleteSlip(slip);
        assertEquals(1, controller.slips(auth("TEACHER", "THPAY")).size());
        assertEquals(1, controller.generate(new PayrollController.Generate("2025-09", teacher), auth("ADMIN", "ADPAY")).get("generated"));
        var regenerated = controller.slips(auth("TEACHER", "THPAY"));
        assertEquals(2, regenerated.size());
        assertNotEquals(slip, regenerated.get(0).get("id"));
        auth("ADMIN", "ADPAY");
        assertEquals(1, controller.deleteMonth("2025-09").get("deleted"));
        var remaining = controller.slips(auth("TEACHER", "THPAY"));
        assertEquals(1, remaining.size());
        assertEquals("2025-08-01", remaining.get(0).get("salary_month").toString());
        assertEquals(1, controller.generate(new PayrollController.Generate("2025-09"), auth("ADMIN", "ADPAY")).get("generated"));
    }
    @Test void teacherCannotGenerateOrEditTeacherPayroll() {
        var teacherAuth = auth("TEACHER", "THPAY");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> controller.deleteSlip(UUID.randomUUID()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> controller.deleteMonth("2025-09"));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> controller.generate(new PayrollController.Generate("2025-09"), teacherAuth));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> controller.createPayroll(new PayrollController.PayrollRequest(teacher, "", new PayrollController.Structure(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))));
    }
    @Test void differentTeachersUseTheirOwnPayrollAndNewTeachersNeedSetup() {
        UUID second = UUID.randomUUID(), unconfigured = UUID.randomUUID();
        jdbc.update("insert into teachers(id, tenant_id, user_id, teacher_id, email, password_hash, display_name) values (?, ?, 'THSECOND', 'EMP-SECOND', 'second@example.invalid', 'unused', 'Second Teacher'), (?, ?, 'THNEW', 'EMP-NEW', 'new@example.invalid', 'unused', 'New Teacher')", second, tenant, unconfigured, tenant);
        controller.createPayroll(new PayrollController.PayrollRequest(teacher, "", new PayrollController.Structure(new BigDecimal("30000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        controller.createPayroll(new PayrollController.PayrollRequest(second, "", new PayrollController.Structure(new BigDecimal("45000"), new BigDecimal("5000"), BigDecimal.ZERO, BigDecimal.ZERO)));
        assertEquals(1, controller.generate(new PayrollController.Generate("2025-08", second), auth("ADMIN", "ADPAY")).get("generated"));
        assertTrue(controller.slips(auth("TEACHER", "THPAY")).isEmpty());
        assertEquals(new BigDecimal("50000.00"), controller.slips(auth("TEACHER", "THSECOND")).get(0).get("net_salary"));
        var result = controller.generate(new PayrollController.Generate("2025-08"), auth("ADMIN", "ADPAY"));
        assertEquals(1, result.get("generated")); assertEquals(1, result.get("skipped"));
        assertEquals(new BigDecimal("30000.00"), controller.slips(auth("TEACHER", "THPAY")).get(0).get("net_salary"));
        assertTrue(controller.slips(auth("TEACHER", "THNEW")).isEmpty());
    }

}
