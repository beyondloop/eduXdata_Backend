package com.edura.schoolos.attendance;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeacherLeaveControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TeacherLeaveController controller = new TeacherLeaveController(jdbc);
    private final UUID tenant = UUID.randomUUID(), teacher = UUID.randomUUID(), admin = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 10, 12);
    private final UsernamePasswordAuthenticationToken teacherAuth = new UsernamePasswordAuthenticationToken("TH001", "", List.of(new SimpleGrantedAuthority("ROLE_TEACHER")));
    private final UsernamePasswordAuthenticationToken adminAuth = new UsernamePasswordAuthenticationToken("AD001", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @BeforeEach void setup() { TenantContext.set(tenant); }
    @AfterEach void cleanup() { TenantContext.clear(); }
    private TeacherLeaveController.Application application(String type, LocalDate end) { return new TeacherLeaveController.Application(type, date, end, "Rest"); }
    private void activeTeacher() { when(jdbc.queryForList(anyString(), eq(tenant), eq("TH001"))).thenReturn(List.of(Map.of("id", teacher))); }
    private void activeAdmin() { when(jdbc.queryForList(anyString(), eq(tenant), eq("AD001"))).thenReturn(List.of(Map.of("id", admin))); }

    @Test void emergencyCannotBypassBalancesAndCanBeSubmittedAfterExhaustion() {
        activeTeacher();
        var file = new MockMultipartFile("proof", "proof.pdf", "application/pdf", "%PDF-test".getBytes());
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> controller.applyWithProof(application("EMERGENCY", date), file, teacherAuth)).getStatusCode().value());
        when(jdbc.queryForList(contains("select leave_type"), eq(tenant), eq(teacher))).thenReturn(List.of(
            Map.of("leave_type", "SICK", "from_date", "2026-01-01", "to_date", "2026-01-05"),
            Map.of("leave_type", "CASUAL", "from_date", "2026-02-01", "to_date", "2026-02-05")));
        assertEquals("PENDING", controller.applyWithProof(application("EMERGENCY", date), file, teacherAuth).get("status"));
    }
    @Test void reversedDatesRejectedWithoutDatabaseAccess() {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.apply(application("SICK", date.minusDays(1)), teacherAuth)).getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
    @Test void singleDaySickAndMultipleDayCasualLeaveStartPending() {
        activeTeacher();
        assertEquals("PENDING", controller.apply(application("SICK", date), teacherAuth).get("status"));
        assertEquals("PENDING", controller.apply(application("CASUAL", date.plusDays(1)), teacherAuth).get("status"));
        verify(jdbc).update(contains("insert into teacher_leave"), any(UUID.class), eq(tenant), eq(teacher), eq("CASUAL"), eq(date), eq(date.plusDays(1)), eq("Rest"), isNull(), isNull(), isNull());
    }
    @Test void overlappingPendingOrApprovedLeaveRejected() {
        activeTeacher();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(tenant), eq(teacher), eq(date), eq(date))).thenReturn(1);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> controller.apply(application("SICK", date), teacherAuth)).getStatusCode().value());
        verify(jdbc, never()).update(contains("insert into"), any(Object[].class));
    }
    @Test void inactiveTeacherCannotApply() {
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.apply(application("SICK", date), teacherAuth)).getStatusCode().value());
    }
    @Test void longLeaveRequiresProofAndAcceptsPdf() {
        activeTeacher();
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.apply(application("SICK", date.plusDays(2)), teacherAuth)).getStatusCode().value());
        byte[] document = "%PDF-1.4 test".getBytes();
        var file = new MockMultipartFile("proof", "certificate.pdf", "application/pdf", document);
        assertEquals("PENDING", controller.applyWithProof(application("SICK", date.plusDays(2)), file, teacherAuth).get("status"));
        verify(jdbc).update(contains("insert into teacher_leave"), any(UUID.class), eq(tenant), eq(teacher), eq("SICK"), eq(date), eq(date.plusDays(2)), eq("Rest"), eq("certificate.pdf"), eq("application/pdf"), eq(document));
    }
    @Test void exhaustedAllowanceRequiresProofEvenForOneDay() {
        activeTeacher();
        when(jdbc.queryForList(contains("select leave_type"), eq(tenant), eq(teacher))).thenReturn(List.of(Map.of("leave_type", "SICK", "from_date", LocalDate.of(2026, 1, 1), "to_date", LocalDate.of(2026, 1, 5))));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.apply(application("SICK", date), teacherAuth)).getStatusCode().value());
        assertEquals("PENDING", controller.apply(application("CASUAL", date), teacherAuth).get("status"));
    }
    @Test void invalidAndOversizedProofRejected() {
        activeTeacher();
        var invalid = new MockMultipartFile("proof", "fake.pdf", "application/pdf", "not a PDF".getBytes());
        var large = new MockMultipartFile("proof", "large.pdf", "application/pdf", new byte[5 * 1024 * 1024 + 1]);
        assertThrows(ResponseStatusException.class, () -> controller.applyWithProof(application("SICK", date), invalid, teacherAuth));
        assertThrows(ResponseStatusException.class, () -> controller.applyWithProof(application("SICK", date), large, teacherAuth));
    }
    @Test void proofDownloadRequiresOwnershipAndTenant() {
        UUID id = UUID.randomUUID();
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> controller.proof(id, teacherAuth)).getStatusCode().value());
        verify(jdbc).queryForList(contains("and t.user_id = ?"), eq(id), eq(tenant), eq("TH001"));
    }
    @Test void adminDownloadsProofAsPrivateAttachment() {
        UUID id = UUID.randomUUID();
        byte[] document = "%PDF-1.4".getBytes();
        when(jdbc.queryForList(contains("ad.active"), eq(id), eq(tenant), eq("AD001"))).thenReturn(List.of(Map.of("proof_data", document, "proof_type", "application/pdf", "proof_name", "certificate.pdf")));
        var response = controller.proof(id, adminAuth);
        assertArrayEquals(document, response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").startsWith("attachment;"));
    }
    @Test void multipartApplicationAcceptsJsonAndProofTogether() throws Exception {
        activeTeacher();
        var payload = new MockMultipartFile("application", "", "application/json", "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-10-12\",\"toDate\":\"2026-10-14\",\"reason\":\"Recovery\"}".getBytes());
        var proof = new MockMultipartFile("proof", "certificate.pdf", "application/pdf", "%PDF-1.4".getBytes());
        MockMvcBuilders.standaloneSetup(controller).build().perform(multipart("/api/v1/teacher-leave").file(payload).file(proof).principal(teacherAuth))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
    }
    @Test void emergencyMultipartPassesValidation() throws Exception {
        activeTeacher();
        when(jdbc.queryForList(contains("select leave_type"), eq(tenant), eq(teacher))).thenReturn(List.of(
            Map.of("leave_type", "SICK", "from_date", "2026-01-01", "to_date", "2026-01-05"),
            Map.of("leave_type", "CASUAL", "from_date", "2026-02-01", "to_date", "2026-02-05")));
        var payload = new MockMultipartFile("application", "", "application/json", """
            {"leaveType":"EMERGENCY","fromDate":"2026-09-28","toDate":"2026-09-30","reason":"Emergency"}
            """.getBytes());
        var proof = new MockMultipartFile("proof", "proof.pdf", "application/pdf", "%PDF-1.4".getBytes());
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new TeacherLeaveValidationHandler()).build()
            .perform(multipart("/api/v1/teacher-leave").file(payload).file(proof).principal(teacherAuth))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
    }
    @Test void multipartApplicationCannotBypassProofRequirement() throws Exception {
        activeTeacher();
        var payload = new MockMultipartFile("application", "", "application/json", "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-10-12\",\"toDate\":\"2026-10-14\",\"reason\":\"Recovery\"}".getBytes());
        MockMvcBuilders.standaloneSetup(controller).build().perform(multipart("/api/v1/teacher-leave").file(payload).principal(teacherAuth))
            .andExpect(status().isBadRequest());
    }
    @Test void oneDayMultipartApplicationDoesNotRequireAttachment() throws Exception {
        activeTeacher();
        var payload = new MockMultipartFile("application", "", "application/json", "{\"leaveType\":\"CASUAL\",\"fromDate\":\"2026-10-12\",\"toDate\":\"2026-10-12\",\"reason\":\"Personal appointment\"}".getBytes());
        MockMvcBuilders.standaloneSetup(controller).build().perform(multipart("/api/v1/teacher-leave").file(payload).principal(teacherAuth))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
    }
    @Test void teacherHistoryIsScopedToTheirAccountAndTenant() {
        controller.list(teacherAuth);
        verify(jdbc).queryForList(contains("and t.user_id = ?"), eq(tenant), eq("TH001"));
    }
    @Test void adminHistoryIsScopedToTenant() {
        controller.list(adminAuth);
        verify(jdbc).queryForList(contains("where l.tenant_id = ?"), eq(tenant));
    }
    @Test void activeAdminCanApproveAndRejectPendingRequests() {
        activeAdmin();
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString(), anyString(), eq(admin), eq(id), eq(tenant))).thenReturn(1);
        assertEquals("APPROVED", controller.decide(id, new TeacherLeaveController.Decision(true), adminAuth).get("status"));
        assertEquals("REJECTED", controller.decide(id, new TeacherLeaveController.Decision(false), adminAuth).get("status"));
        verify(jdbc).update(contains("and status = 'PENDING'"), eq("APPROVED"), eq(admin), eq(id), eq(tenant));
    }
    @Test void missingOtherTenantOrReviewedRequestCannotBeDecided() {
        activeAdmin();
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> controller.decide(UUID.randomUUID(), new TeacherLeaveController.Decision(true), adminAuth)).getStatusCode().value());
    }
    @Test void inactiveAdminCannotReview() {
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.decide(UUID.randomUUID(), new TeacherLeaveController.Decision(true), adminAuth)).getStatusCode().value());
    }
    @Test void validatesTypesDatesReasonLengthAndDecision() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(application("OTHER", date)).isEmpty());
            assertFalse(validator.validate(new TeacherLeaveController.Application("SICK", null, date, "")).isEmpty());
            assertFalse(validator.validate(new TeacherLeaveController.Application("SICK", date, date, "x".repeat(1001))).isEmpty());
            assertFalse(validator.validate(new TeacherLeaveController.Decision(null)).isEmpty());
            assertFalse(validator.validate(new TeacherLeaveController.Application("SICK", date, date, " ")).isEmpty());
        }
    }
}
