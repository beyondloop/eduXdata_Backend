package com.edura.schoolos.attendance;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudentLeaveControllerTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final StudentLeaveController controller = new StudentLeaveController(jdbc);
    final UUID tenant = UUID.randomUUID(), id = UUID.randomUUID(), student = UUID.randomUUID();
    final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("TH001", "unused");
    @BeforeEach void setup() { TenantContext.set(tenant); }
    @AfterEach void cleanup() { TenantContext.clear(); }
    void request() {
        when(jdbc.queryForList(anyString(), eq(id), eq(tenant), eq("TH001"))).thenReturn(List.of(Map.of(
            "student_id", student, "from_date", LocalDate.of(2026,10,30), "to_date", LocalDate.of(2026,11,1))));
    }
    @Test void approvalMarksEveryDateAcrossMonthBoundaryAbsent() {
        request();
        assertEquals("APPROVED", controller.decide(id, new StudentLeaveController.Decision(true), auth).get("status"));
        for (var date : List.of(LocalDate.of(2026,10,30), LocalDate.of(2026,10,31), LocalDate.of(2026,11,1))) {
            verify(jdbc).update(contains("insert into attendance"), any(UUID.class), eq(tenant), eq(student), eq(date));
        }
    }
    @Test void rejectionDoesNotChangeAttendance() {
        request();
        assertEquals("REJECTED", controller.decide(id, new StudentLeaveController.Decision(false), auth).get("status"));
        verify(jdbc, never()).update(contains("insert into attendance"), any(UUID.class), any(UUID.class), any(UUID.class), any(LocalDate.class));
    }
    @Test void otherTeacherOrReviewedRequestCannotBeDecided() {
        assertThrows(ResponseStatusException.class, () -> controller.decide(id, new StudentLeaveController.Decision(true), auth));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void reversedDatesRejectedBeforeDatabaseAccess() {
        assertThrows(ResponseStatusException.class, () -> controller.apply(new StudentLeaveController.Application("SICK", LocalDate.of(2026,11,2), LocalDate.of(2026,11,1), "Unwell"), auth));
        verifyNoInteractions(jdbc);
    }
    @Test void missingClassTeacherPreventsSubmission() {
        when(jdbc.queryForList(contains("select id from students"), eq(tenant), eq("TH001"))).thenReturn(List.of(Map.of("id", student)));
        var failure = assertThrows(ResponseStatusException.class, () -> controller.apply(new StudentLeaveController.Application("SICK", LocalDate.of(2026,11,1), LocalDate.of(2026,11,1), "Unwell"), auth));
        assertEquals(409, failure.getStatusCode().value());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    void assignedStudent() {
        when(jdbc.queryForList(contains("select id from students"), eq(tenant), eq("TH001"))).thenReturn(List.of(Map.of("id", student)));
        when(jdbc.queryForList(contains("select distinct w.teacher_user_id"), eq(student), eq(tenant))).thenReturn(List.of(Map.of("teacher_user_id", "TH002")));
    }
    @Test void submissionRoutesToAssignedTeacherAndTrimsComments() {
        assignedStudent();
        var date = LocalDate.of(2026,11,1);
        assertEquals("PENDING", controller.apply(new StudentLeaveController.Application("SICK", date, date, " Unwell "), auth).get("status"));
        verify(jdbc).update(contains("insert into student_leave"), any(UUID.class), eq(tenant), eq(student), eq("TH002"), eq("SICK"), eq(date), eq(date), eq("Unwell"));
    }
    @Test void overlappingLeaveCannotBeSubmitted() {
        assignedStudent();
        var date = LocalDate.of(2026,11,1);
        when(jdbc.queryForObject(contains("select count(*) from student_leave"), eq(Integer.class), eq(tenant), eq(student), eq(date), eq(date))).thenReturn(1);
        assertThrows(ResponseStatusException.class, () -> controller.apply(new StudentLeaveController.Application("SICK", date, date, "Unwell"), auth));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
