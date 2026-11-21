package com.edura.schoolos.finance;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PayrollControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PayrollController controller = new PayrollController(jdbc);
    private final UUID tenant = UUID.randomUUID(), teacher = UUID.randomUUID();
    private UsernamePasswordAuthenticationToken auth(String role) { return new UsernamePasswordAuthenticationToken("USER001", "", List.of(new SimpleGrantedAuthority("ROLE_" + role))); }
    @BeforeEach void setup() { TenantContext.set(tenant); }
    @AfterEach void cleanup() { TenantContext.clear(); }
    private void configured() {
        when(jdbc.queryForObject(contains("select name from tenants"), eq(String.class), eq(tenant))).thenReturn("School");
        when(jdbc.queryForList(contains("from teachers"), eq(tenant))).thenReturn(List.of(Map.of("id", teacher, "display_name", "Teacher", "employee_code", "EMP001", "pf_number", "", "basic_salary", new BigDecimal("30000"), "allowances", BigDecimal.ZERO, "pf_deduction", BigDecimal.ZERO, "other_deductions", BigDecimal.ZERO)));
    }
    @Test void teacherCanOnlyListOwnTenantAndAccountSlips() {
        controller.slips(auth("TEACHER"));
        verify(jdbc).queryForList(contains("s.tenant_id = ? and t.user_id = ?"), eq(tenant), eq("USER001"));
    }
    @Test void adminIsScopedToOwnTenant() {
        controller.slips(auth("ADMIN"));
        verify(jdbc).queryForList(contains("s.tenant_id = ? order by"), eq(tenant));
    }
    @Test void generationRequiresSavedStructureAndValidMonth() {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.generate(new PayrollController.Generate("2026-99"), auth("ADMIN"))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.generate(new PayrollController.Generate("2025-01"), auth("ADMIN"))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.generate(new PayrollController.Generate(YearMonth.now().plusYears(1).toString()), auth("ADMIN"))).getStatusCode().value());
    }
    @Test void existingSlipsAreNeverOverwritten() {
        configured();
        when(jdbc.queryForObject(contains("count(*) from teacher_salary_slips"), eq(Integer.class), eq(tenant), eq(teacher), any())).thenReturn(1);
        var result = controller.generate(new PayrollController.Generate("2025-01"), auth("ADMIN"));
        assertEquals(0, result.get("generated")); assertEquals(1, result.get("skipped"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void pendingLeaveBlocksGeneration() {
        configured();
        when(jdbc.queryForList(contains("from teacher_leave"), eq(tenant), eq(teacher))).thenReturn(List.of(Map.of("status", "PENDING", "from_date", "2025-01-02", "to_date", "2025-01-03")));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> controller.generate(new PayrollController.Generate("2025-01"), auth("ADMIN"))).getStatusCode().value());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void attendanceLookupIsScopedToTenantAndExactMonth() {
        controller.days("2024-02");
        verify(jdbc).queryForList(contains("teacher_attendance where tenant_id = ? and attendance_date between ? and ?"),
            eq(tenant), eq(java.time.LocalDate.of(2024, 2, 1)), eq(java.time.LocalDate.of(2024, 2, 29)));
        verify(jdbc).queryForList(contains("teacher_leave where tenant_id = ? and status = 'APPROVED'"), eq(tenant));
    }
    @Test void invalidOrUnselectedDayOverridesAreRejected() {
        configured();
        for (var overrides : List.of(Map.of(teacher, 32), Map.of(teacher, -1), Map.of(UUID.randomUUID(), 28))) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.generate(new PayrollController.Generate("2025-01", null, overrides), auth("ADMIN"))).getStatusCode().value());
        }
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void employeeUpdateCannotCrossTenantBoundary() {
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> controller.updateEmployee(teacher, new PayrollController.Employee("PF123"))).getStatusCode().value());
        verify(jdbc).update(contains("id = ? and tenant_id = ?"), eq("PF123"), eq(teacher), eq(tenant));
    }
    @Test void deletionIsScopedToTenantAndSlip() {
        UUID slip = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(tenant), eq(slip))).thenReturn(1);
        controller.deleteSlip(slip);
        verify(jdbc).update("delete from teacher_salary_slips where tenant_id = ? and id = ?", tenant, slip);
    }
    @Test void missingOrForeignSlipReturnsNotFound() {
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> controller.deleteSlip(UUID.randomUUID())).getStatusCode().value());
    }
    @Test void monthlyDeletionRequiresValidMonthAndScopesToTenant() {
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> controller.deleteMonth("2025-99")).getStatusCode().value());
        verifyNoInteractions(jdbc);
        var date = java.time.LocalDate.of(2025, 9, 1);
        when(jdbc.update(anyString(), eq(tenant), eq(date))).thenReturn(2);
        assertEquals(2, controller.deleteMonth("2025-09").get("deleted"));
        verify(jdbc).update("delete from teacher_salary_slips where tenant_id = ? and salary_month = ?", tenant, date);
    }
    @Test void rejectsStructureWithExcessDeductions() {
        assertThrows(ResponseStatusException.class, () -> controller.createPayroll(new PayrollController.PayrollRequest(teacher, "PF123", new PayrollController.Structure(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO))));
        verifyNoInteractions(jdbc);
    }
}
