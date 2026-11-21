package com.edura.schoolos.notification;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationController controller = new NotificationController(jdbc);
    private final UUID tenant = UUID.randomUUID();
    @BeforeEach void setup() { TenantContext.set(tenant); }
    @AfterEach void cleanup() { TenantContext.clear(); }
    private UsernamePasswordAuthenticationToken auth(String role, String id) { return new UsernamePasswordAuthenticationToken(id, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))); }
    private Map<String, Object> leave(String status) {
        var entry = new HashMap<String, Object>();
        entry.put("id", UUID.randomUUID()); entry.put("leave_type", "SICK"); entry.put("from_date", LocalDate.of(2026,9,24)); entry.put("to_date", LocalDate.of(2026,9,24));
        entry.put("teacher_id", "teacher-one"); entry.put("status", status); entry.put("submitted_at", Timestamp.valueOf("2026-09-23 09:00:00")); entry.put("teacher_name", "Teacher One");
        if (!status.equals("PENDING")) { entry.put("reviewed_at", Timestamp.valueOf("2026-09-24 09:00:00")); entry.put("approver_name", "Approver One"); }
        return entry;
    }
    @Test void teacherReceivesOwnSubmissionAndDecisionInNewestFirstOrder() {
        when(jdbc.queryForList(contains("and t.user_id = ?"), eq(tenant), eq("TH001"))).thenReturn(List.of(leave("APPROVED")));
        var notices = controller.list(auth("TEACHER", "TH001"));
        assertEquals(2, notices.size()); assertEquals("Leave approved", notices.get(0).get("title"));
        assertTrue(notices.get(0).get("body").toString().contains("Approver One"));
        assertEquals("My Attendance", notices.get(0).get("destination"));
        verify(jdbc).queryForList(contains("where l.tenant_id = ? and t.user_id = ?"), eq(tenant), eq("TH001"));
    }
    @Test void rejectedLeaveProducesDecisionNotification() {
        when(jdbc.queryForList(contains("and t.user_id = ?"), eq(tenant), eq("TH001"))).thenReturn(List.of(leave("REJECTED")));
        assertEquals("Leave rejected", controller.list(auth("TEACHER", "TH001")).get(0).get("title"));
    }
    @Test void studentCannotReadTeacherLeaveNotifications() {
        assertTrue(controller.list(auth("STUDENT", "ST001")).isEmpty());
        verify(jdbc, never()).queryForList(contains("teacher_leave"), any(Object[].class));
    }
    @Test void adminSeesPendingRequestsWithReviewLink() {
        when(jdbc.queryForList(contains("where l.tenant_id = ?"), eq(tenant))).thenReturn(List.of(leave("PENDING")));
        var notices = controller.list(auth("ADMIN", "AD001"));
        assertEquals(1, notices.size()); assertEquals("Leave Approvals", notices.get(0).get("destination"));
        assertTrue(notices.get(0).get("body").toString().contains("paid days"));
    }
    @Test void adminGetsStudentSpecificDisciplineApprovalNotification() {
        UUID student = UUID.randomUUID();
        when(jdbc.queryForList(contains("d.status = 'PENDING'"), eq(tenant))).thenReturn(List.of(Map.of(
            "id", UUID.randomUUID(), "student_id", student, "incident", "Mobile use", "points", 20,
            "created_at", Timestamp.valueOf("2026-09-28 10:00:00"), "display_name", "Student One", "user_id", "ST001")));
        var notices = controller.list(auth("ADMIN", "AD001"));
        assertEquals(1, notices.size());
        assertEquals("DISCIPLINE", notices.get(0).get("source"));
        assertEquals("Discipline Points", notices.get(0).get("destination"));
        assertEquals(student, notices.get(0).get("student_id"));
        assertTrue(notices.get(0).get("body").toString().contains("Student One (ST001): Mobile use - 20 points"));
    }
    @Test void studentLeaveNotificationRoutesOnlyToAssignedTeacher() {
        var row = new HashMap<>(leave("PENDING"));
        row.put("student_name", "Student One"); row.put("comments", "Unwell");
        when(jdbc.queryForList(contains("and l.teacher_user_id = ? and l.status = 'PENDING'"), eq(tenant), eq("TH001"))).thenReturn(List.of(row));
        var notices = controller.list(auth("TEACHER", "TH001"));
        assertEquals(1, notices.size());
        assertEquals("Student Leave Approvals", notices.get(0).get("destination"));
        assertTrue(notices.get(0).get("body").toString().contains("Unwell"));
        assertTrue(controller.list(auth("TEACHER", "TH002")).isEmpty());
    }
    @Test void studentReceivesOwnLeaveDecision() {
        var row = new HashMap<>(leave("APPROVED"));
        row.put("student_name", "Student One"); row.put("comments", "Unwell");
        when(jdbc.queryForList(contains("and s.user_id = ?"), eq(tenant), eq("ST001"))).thenReturn(List.of(row));
        var notices = controller.list(auth("STUDENT", "ST001"));
        assertEquals("Leave approved", notices.get(0).get("title"));
        assertEquals("Leave Management", notices.get(0).get("destination"));
        assertTrue(controller.list(auth("STUDENT", "ST002")).isEmpty());
    }
    @Test void disciplineApprovalNotificationsAreAdminOnly() {
        controller.list(auth("TEACHER", "TH001"));
        controller.list(auth("STUDENT", "ST001"));
        verify(jdbc, never()).queryForList(contains("student_discipline"), eq(tenant));
    }
}
