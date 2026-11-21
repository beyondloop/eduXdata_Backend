package com.edura.schoolos.attendance;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttendanceControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AttendanceController controller = new AttendanceController(jdbc);
    private final UUID tenant = UUID.randomUUID(), student = UUID.randomUUID(), actor = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 10, 27);
    private final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        "TH001", "unused", List.of(new SimpleGrantedAuthority("ROLE_TEACHER")));

    @BeforeEach void setup() {
        TenantContext.set(tenant);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(student), eq(tenant))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(student), eq(tenant), eq("TH001"))).thenReturn(1);
        when(jdbc.queryForList(anyString(), eq(tenant), eq("TH001"))).thenReturn(List.of(Map.of("id", actor)));
    }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void recordsAuthenticatedMarkerAndUpdatesSameStudentDate() {
        controller.save(new AttendanceController.AttendanceEntry(student, date, "present"), auth);
        controller.save(new AttendanceController.AttendanceEntry(student, date, "ABSENT"), auth);
        for (String status : List.of("PRESENT", "ABSENT")) {
            verify(jdbc).update(contains("do update set status = excluded.status, marked_by = excluded.marked_by"),
                any(UUID.class), eq(tenant), eq(student), eq(date), eq(status), eq(actor));
        }
    }

    @Test void unassignedTeacherCannotWriteAttendance() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(student), eq(tenant), eq("TH001"))).thenReturn(0);
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> controller.save(new AttendanceController.AttendanceEntry(student, date, "PRESENT"), auth)).getStatusCode().value());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
