package com.edura.schoolos.dashboard;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@Transactional
@EnabledIfEnvironmentVariable(named = "ADMIN_DATA_INTEGRATION_TESTS", matches = "true")
class AdminDataIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminDataController controller;
    @Autowired com.edura.schoolos.tenant.TenantController tenantController;
    @Autowired com.edura.schoolos.student.StudentController studentController;
    @Autowired AdminAcademicsController academics;
    @Autowired SeparationController separations;
    @Autowired com.edura.schoolos.student.StudentFeesController feesController;
    @Autowired com.edura.schoolos.finance.FinancialStructureController structures;
    UUID tenant = UUID.randomUUID(), student = UUID.randomUUID(), otherTenant = UUID.randomUUID();
    LocalDate day = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    UsernamePasswordAuthenticationToken admin;

    @BeforeEach void setup() {
        TenantContext.set(tenant);
        admin = auth("ADMIN");
        jdbc.update("insert into tenants(id, name, slug) values (?, 'Admin data test', ?), (?, 'Other school', ?)", tenant, tenant.toString(), otherTenant, otherTenant.toString());
        jdbc.update("insert into students(id, tenant_id, admission_number, display_name, user_id, class_name, section_name) values (?, ?, 'STTEST', 'Real Student', 'STTEST', '10', 'A')", student, tenant);
        jdbc.update("insert into students(id, tenant_id, admission_number, display_name, user_id) values (?, ?, 'OTHER', 'Other Student', 'OTHER')", UUID.randomUUID(), otherTenant);
        jdbc.update("insert into admins(id, tenant_id, user_id, email, password_hash, display_name) values (?, ?, 'ADTEST', 'admin@example.invalid', 'unused', 'Test Admin')", UUID.randomUUID(), tenant);
    }

    UsernamePasswordAuthenticationToken auth(String role) {
        var value = new UsernamePasswordAuthenticationToken("ADTEST", "", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(value);
        return value;
    }
    @AfterEach void cleanup() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    @Test void overviewUsesTenantRecordsAndSuccessfulPartialPayments() {
        UUID invoice = UUID.randomUUID();
        jdbc.update("insert into invoices(id, tenant_id, student_id, invoice_number, amount, month_start) values (?, ?, ?, 'TEST', 1000, ?)", invoice, tenant, student, day.withDayOfMonth(1));
        jdbc.update("insert into payments(tenant_id, invoice_id, amount, status, paid_at) values (?, ?, 250, 'SUCCESS', now()), (?, ?, 500, 'FAILED', now())", tenant, invoice, tenant, invoice);
        jdbc.update("insert into attendance(tenant_id, student_id, attendance_date, status) values (?, ?, ?, 'ABSENT')", tenant, student, day);
        var result = controller.overview();
        assertEquals(1L, result.get("students"));
        assertEquals(0L, result.get("pendingApprovals"));
        var attendance = (Map<?, ?>) result.get("attendance");
        assertEquals(1L, attendance.get("marked")); assertEquals(0L, attendance.get("present"));
        var fees = (Map<?, ?>) result.get("fees");
        assertEquals(0, new BigDecimal("250").compareTo((BigDecimal) fees.get("collected")));
        assertEquals(0, new BigDecimal("750").compareTo((BigDecimal) fees.get("pending")));
        assertEquals(1, ((List<?>) result.get("recentAttendance")).size());
        assertEquals(1, ((List<?>) result.get("attendanceTrend")).size());
        var studentRow = studentController.list("STTEST", admin).get(0);
        assertEquals(0, new BigDecimal("250").compareTo((BigDecimal) studentRow.get("paid_fee_amount")));
        assertEquals(0, new BigDecimal("750").compareTo((BigDecimal) studentRow.get("pending_fee_amount")));
        assertEquals("Admin data test", tenantController.context().get("name"));
    }

    @Test void recordScreensUseRealResultsAndEmptyDatasets() {
        UUID exam = UUID.randomUUID(), subject = UUID.randomUUID();
        jdbc.update("insert into exams(id, tenant_id, name) values (?, ?, 'Database Exam')", exam, tenant);
        jdbc.update("insert into subjects(id, tenant_id, name) values (?, ?, 'Database Subject')", subject, tenant);
        jdbc.update("insert into results(tenant_id, student_id, exam_id, subject_id, marks, grade) values (?, ?, ?, ?, 37, 'B')", tenant, student, exam, subject);
        assertEquals("Database Exam", controller.records("exams", null, null).get(0).get("exam"));
        assertEquals("Database Subject", controller.records("results", null, null).get(0).get("subject"));
        assertEquals(List.of("Database Subject"), controller.subjects());
        assertEquals(1, controller.records("performance", null, null).size());
        assertTrue(controller.records("attendance", day, day).isEmpty());
        assertTrue(controller.records("attendance-analytics", day, day).isEmpty());
        assertTrue(controller.records("reports", null, null).isEmpty());
        assertTrue(controller.records("documents", null, null).isEmpty());
        assertTrue(controller.records("student-documents", null, null).isEmpty());
        assertEquals(1, ((List<?>) controller.studentInsights(student).get("results")).size());
        assertThrows(ResponseStatusException.class, () -> controller.records("unknown", null, null));
        assertThrows(ResponseStatusException.class, () -> controller.records("attendance", day, day.minusDays(1)));
    }

    @Test void documentsRoundTripAndStayWithinTheirTenant() throws Exception {
        byte[] content = "student record".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID id = (UUID) controller.uploadDocument(student, new MockMultipartFile("file", "student.txt", "text/plain", content), admin).get("id");
        assertEquals(1, controller.records("student-documents", null, null).size());
        assertArrayEquals(content, controller.downloadDocument(id).getBody());
        TenantContext.set(otherTenant);
        assertTrue(controller.records("student-documents", null, null).isEmpty());
        assertThrows(ResponseStatusException.class, () -> controller.downloadDocument(id));
        assertThrows(ResponseStatusException.class, () -> controller.studentInsights(student));
    }

    @Test void adminProfilePersistsAndNonAdminsCannotReadAdminData() {
        var saved = controller.saveProfile(new AdminDataController.AdminProfile("Updated Admin", "updated@example.invalid", null), admin);
        assertEquals("Updated Admin", saved.get("displayName"));
        assertEquals("updated@example.invalid", controller.profile(admin).get("email"));
        auth("TEACHER");
        assertThrows(AccessDeniedException.class, () -> controller.overview());
        assertThrows(AccessDeniedException.class, () -> controller.records("performance", null, null));
        assertThrows(AccessDeniedException.class, () -> controller.profile(admin));
    }

    @Test void separationRequestsAndChecksPersistAndAreTenantScoped() {
        assertTrue(separations.list().isEmpty());
        separations.addTemplate(new SeparationController.Template("STUDENT", "Check fee clearance"));
        UUID id = (UUID) separations.create(new SeparationController.Request("STUDENT", student, day, "Transfer request"), admin).get("id");
        var saved = separations.list().get(0);
        assertEquals("Real Student", saved.get("person_name"));
        assertThrows(ResponseStatusException.class, () -> separations.complete(id));
        var items = (List<Map<String, Object>>) saved.get("checklist");
        separations.updateItem(id, (UUID) items.get(0).get("id"), new SeparationController.Check(true), admin);
        assertEquals("COMPLETED", separations.complete(id).get("status"));
        assertEquals("COMPLETED", separations.list().get(0).get("status"));
        assertTrue(jdbc.queryForObject("select active from students where id = ?", Boolean.class, student));
        TenantContext.set(otherTenant);
        assertTrue(separations.list().isEmpty());
        assertThrows(ResponseStatusException.class, () -> separations.complete(id));
    }

    @Test void academicFormsSaveAndUpdateDatabaseResults() {
        UUID year = (UUID) academics.saveYear(new AdminAcademicsController.Year("2033-34", LocalDate.of(2033, 6, 1), LocalDate.of(2034, 5, 31), true)).get("id");
        UUID subject = (UUID) academics.createSubject(new AdminAcademicsController.Name("Physics")).get("id");
        UUID exam = (UUID) academics.createExam(new AdminAcademicsController.Exam("Final", year, java.time.LocalDate.of(2025, 5, 12))).get("id");
        UUID id = (UUID) academics.createResult(new AdminAcademicsController.Result(student, exam, subject, new BigDecimal("37.50"), "B")).get("id");
        assertEquals(id, controller.records("results", null, null).get(0).get("id"));
        academics.updateResult(id, new AdminAcademicsController.Result(student, exam, subject, new BigDecimal("42.25"), "A"));
        assertEquals(new BigDecimal("42.25"), controller.records("results", null, null).get(0).get("marks"));
        assertEquals(1, ((List<?>) academics.options().get("years")).size());
        assertThrows(ResponseStatusException.class, () -> academics.createResult(new AdminAcademicsController.Result(student, exam, subject, BigDecimal.TEN, "B")));
        TenantContext.set(otherTenant);
        assertThrows(ResponseStatusException.class, () -> academics.updateResult(id, new AdminAcademicsController.Result(student, exam, subject, BigDecimal.ONE, "B")));
    }

    @Test void feesUseConfiguredClassesAmountsAndAcademicDatesWithoutFallbackInvoices() {
        assertTrue(feesController.list(student).isEmpty());
        academics.saveYear(new AdminAcademicsController.Year("2033-34", LocalDate.of(2033, 6, 1), LocalDate.of(2034, 5, 31), true));
        assertTrue(feesController.list(student).isEmpty());
        jdbc.update("insert into classes(tenant_id, name) values (?, 'Senior Secondary')", tenant);
        jdbc.update("update students set class_name = 'Senior Secondary' where id = ?", student);
        structures.saveFeeStructure(new com.edura.schoolos.finance.FinancialStructureController.FeeStructureRequest("Senior Secondary", new BigDecimal("1234"), new BigDecimal("56"), new BigDecimal("78")));
        var rows = feesController.list(student);
        assertEquals(25, rows.size());
        assertEquals("2033-06-01", jdbc.queryForObject("select min(month_start) from invoices where tenant_id = ?", java.sql.Date.class, tenant).toString());
        assertEquals("2034-05-01", jdbc.queryForObject("select max(month_start) from invoices where tenant_id = ?", java.sql.Date.class, tenant).toString());
        assertEquals("2033-34", jdbc.queryForObject("select distinct academic_year from invoices where tenant_id = ?", String.class, tenant));
        assertEquals(25L, feesController.feeCollections(null).get(0).get("invoice_count"));
        assertEquals(0, new BigDecimal("1234").compareTo((BigDecimal) rows.stream().filter(row -> row.get("fee_type").equals("Tuition fee")).findFirst().orElseThrow().get("amount")));
        assertThrows(ResponseStatusException.class, () -> structures.saveFeeStructure(new com.edura.schoolos.finance.FinancialStructureController.FeeStructureRequest("Nonexistent class", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO)));
    }

    @Test void generalSchoolDocumentsCanBeSavedWithoutAStudent() throws Exception {
        UUID id = (UUID) controller.uploadDocument(null, new MockMultipartFile("file", "school.txt", "text/plain", new byte[]{1, 2, 3}), admin).get("id");
        assertEquals("SCHOOL", controller.records("documents", null, null).get(0).get("source"));
        assertArrayEquals(new byte[]{1, 2, 3}, controller.downloadDocument(id).getBody());
        assertTrue(controller.records("student-documents", null, null).isEmpty());
    }
}
