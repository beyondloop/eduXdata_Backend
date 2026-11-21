package com.edura.schoolos.attendance;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeacherAttendanceControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TeacherAttendanceController controller = new TeacherAttendanceController(jdbc);
    private final UUID tenant = UUID.randomUUID();
    private final UUID teacher = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("TH001", "unused");

    @BeforeEach void setup() {
        TenantContext.set(tenant);
        when(jdbc.queryForList(anyString(), eq(tenant), eq("TH001"))).thenReturn(List.of(Map.of("id", teacher)));
        when(jdbc.update(anyString(), any(UUID.class), eq(tenant), eq(teacher), any(LocalDate.class), any(BigDecimal.class), anyString(), nullable(UUID.class))).thenReturn(1);
    }
    @AfterEach void cleanup() { TenantContext.clear(); }
    private TeacherAttendanceController.Entry entry(LocalDate date, UUID approver) {
        return new TeacherAttendanceController.Entry(date, new BigDecimal("9"), approver);
    }
    @Test void futureIsRejectedBeforeDatabaseAccess() {
        assertThrows(ResponseStatusException.class, () -> controller.submit(entry(today.plusDays(1), null), auth));
        verifyNoInteractions(jdbc);
    }
    @Test void todayAndTwoPreviousDaysSubmitDirectly() {
        for (int days = 0; days <= 2; days++) assertEquals("PRESENT", controller.submit(entry(today.minusDays(days), null), auth).get("status"));
    }
    @Test void olderDateRequiresApprover() {
        assertThrows(ResponseStatusException.class, () -> controller.submit(entry(today.minusDays(3), null), auth));
    }
    @Test void olderDateWaitsForValidAdmin() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(tenant), eq(admin))).thenReturn(1);
        assertEquals("PENDING", controller.submit(entry(today.minusDays(3), admin), auth).get("status"));
    }
    @Test void invalidOrOtherTenantApproverIsRejected() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(tenant), eq(admin))).thenReturn(0);
        assertThrows(ResponseStatusException.class, () -> controller.submit(entry(today.minusDays(3), admin), auth));
    }
    @Test void duplicateSubmissionIsConflict() {
        when(jdbc.update(anyString(), any(UUID.class), eq(tenant), eq(teacher), any(LocalDate.class), any(BigDecimal.class), anyString(), nullable(UUID.class))).thenReturn(0);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> controller.submit(entry(today, null), auth)).getStatusCode().value());
    }
    @Test void unassignedOrAlreadyReviewedRequestCannotBeDecided() {
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> controller.decide(UUID.randomUUID(), new TeacherAttendanceController.Decision(true), auth)).getStatusCode().value());
    }
}
