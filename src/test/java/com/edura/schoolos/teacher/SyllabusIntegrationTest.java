package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "SYLLABUS_INTEGRATION_TESTS", matches = "true")
class SyllabusIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SyllabusExcelController excel;
    @Autowired SyllabusController controller;
    @Autowired TeacherPerformanceController performance;
    @Autowired SyllabusDocumentController documents;
    @Autowired Validator validator;
    @Autowired MockMvc mvc;
    final UUID tenant = UUID.randomUUID();
    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    final byte[] pdf = "%PDF-1.4\nSyllabus reference".getBytes(StandardCharsets.US_ASCII);

    UsernamePasswordAuthenticationToken auth(String role, String user) {
        var auth = new UsernamePasswordAuthenticationToken(user, "", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }
    @BeforeEach void setup() {
        TenantContext.set(tenant);
        jdbc.update("insert into tenants(id, name, slug) values (?, 'Syllabus test school', ?)", tenant, "syllabus-test-" + tenant);
        jdbc.update("insert into teachers(id, tenant_id, user_id, teacher_id, email, password_hash, display_name) values (?, ?, 'THSYL', 'EMP-SYL', 'syllabus@example.invalid', 'unused', 'Syllabus Teacher')", UUID.randomUUID(), tenant);
        jdbc.update("""
            insert into teacher_workloads(id, tenant_id, teacher_user_id, teacher_name, teacher_role, subject, class_name, section_name, day_of_week, start_time, end_time, duration_minutes)
            values (?, ?, 'THSYL', 'Syllabus Teacher', 'Subject teacher', 'Mathematics', '10', 'A', 'Monday', '10:00', '11:00', 60)
            """, UUID.randomUUID(), tenant);
    }
    @AfterEach void cleanup() { TenantContext.clear(); SecurityContextHolder.clearContext(); }
    SyllabusController.CreateSyllabus entry(String section, String subject) {
        return new SyllabusController.CreateSyllabus("Term 1", "10", section, subject, List.of(
            new SyllabusController.TopicInput("Algebra", today),
            new SyllabusController.TopicInput("Geometry", today.minusDays(1))));
    }
    UUID create() {
        return (UUID) controller.create(entry("A", "Mathematics"), new MockMultipartFile("file", "syllabus.pdf", "application/pdf", pdf), auth("ADMIN", "ADSYL")).get("id");
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> topics(Map<String, Object> plan) { return (List<Map<String, Object>>) plan.get("topics"); }

    @Test void teacherCanReadAssignedDocumentAddDailyNotesAndSubmitOnTimeOrLate() {
        UUID planId = create();
        var teacher = auth("TEACHER", "THSYL");
        var plan = controller.list(teacher).get(0);
        assertEquals("Syllabus Teacher", plan.get("teacher_names"));
        var topics = topics(plan);
        assertEquals("PENDING", topics.get(0).get("status"));
        assertEquals("OVERDUE", topics.get(1).get("status"));
        assertArrayEquals(pdf, controller.download(planId, teacher).getBody());
        UUID first = (UUID) topics.get(0).get("id"), second = (UUID) topics.get(1).get("id");
        controller.addNote(first, new SyllabusController.NoteInput("Covered linear equations"), teacher);
        controller.addNote(first, new SyllabusController.NoteInput("Practised examples"), teacher);
        assertEquals("IN_PROGRESS", topics(controller.list(teacher).get(0)).get(0).get("status"));
        controller.complete(first, new SyllabusController.Completion(true), teacher);
        controller.complete(second, new SyllabusController.Completion(true), teacher);
        var tracked = topics(controller.list(auth("ADMIN", "ADSYL")).get(0));
        assertEquals("COMPLETED", tracked.get(0).get("status"));
        assertEquals("COMPLETED_LATE", tracked.get(1).get("status"));
        assertEquals(5, tracked.get(0).get("points"));
        assertEquals(0, tracked.get(1).get("points"));
        assertEquals(1, tracked.get(1).get("days_late"));
        assertEquals("THSYL", tracked.get(0).get("completed_by"));
        assertEquals(2, ((List<?>) tracked.get(0).get("notes")).size());
    }

    @Test void configurableAwardsPerformanceRemarksAndStudentPrivacy() {
        UUID planId=create();
        var admin=auth("ADMIN","ADSYL");
        performance.rules(new TeacherPerformanceController.Rules(7,2));
        UUID id=(UUID)topics(controller.list(admin).get(0)).get(0).get("id");
        controller.assignTeacher(planId,new SyllabusController.TeacherAssignment("THSYL"));
        controller.complete(id,new SyllabusController.Completion(true),auth("TEACHER","THSYL"));
        admin=auth("ADMIN","ADSYL");
        assertEquals(7,topics(controller.list(admin).get(0)).get(0).get("points"));
        performance.rules(new TeacherPerformanceController.Rules(9,1));
        assertEquals(7,topics(controller.list(admin).get(0)).get(0).get("points"));
        assertEquals(2,((List<?>)performance.list().get("topics")).size());
        performance.remarks("THSYL",new TeacherPerformanceController.Remark("General","Consistent progress"),admin);
        assertEquals(1,((List<?>)performance.list().get("remarks")).size());
        jdbc.update("insert into students(id,tenant_id,admission_number,display_name,user_id,class_name,section_name) values(?,?,'STSYL','Test student','STSYL','Class 10','A')",UUID.randomUUID(),tenant);
        var student=auth("STUDENT","STSYL");
        var studentTopics=topics(controller.list(student).get(0));
        assertNotNull(studentTopics.get(0).get("completed_at"));
        assertFalse(studentTopics.get(0).containsKey("points"));
        assertArrayEquals(pdf,controller.download(planId,student).getBody());
        assertThrows(AccessDeniedException.class,()->performance.list());
        assertThrows(AccessDeniedException.class,()->performance.rules(new TeacherPerformanceController.Rules(100,100)));
        auth("TEACHER","THSYL");
        assertThrows(AccessDeniedException.class,()->performance.list());
        assertThrows(ResponseStatusException.class,()->controller.complete(id,new SyllabusController.Completion(true),auth("TEACHER","THSYL")));
    }

    @Test void finalTermTopicMaintenancePdfAndAssignmentValidation() {
        var admin=auth("ADMIN","ADSYL");
        LocalDate start=LocalDate.of(today.getMonthValue()<4 ? today.getYear()-1 : today.getYear(),4,1);
        var annual=new SyllabusController.AnnualSyllabus("Final plan","10","A",start,
            List.of(new SyllabusController.SubjectTargets("Mathematics",List.of(new SyllabusController.TopicInput("Revision",today)),"THSYL")),"FINAL");
        UUID planId=(UUID)((List<?>)controller.createAnnual(annual,null,admin).get("ids")).get(0);
        assertEquals("FINAL",controller.list(admin).get(0).get("period"));
        assertThrows(ResponseStatusException.class,()->controller.assignTeacher(planId,new SyllabusController.TeacherAssignment("OTHER")));
        UUID topicId=(UUID)controller.addTopic(planId,new SyllabusController.TopicInput("Practice",today)).get("id");
        controller.updateTopic(topicId,new SyllabusController.TopicInput("Practice revised",today.plusDays(1)),admin);
        assertThrows(ResponseStatusException.class,()->controller.addTopic(planId,new SyllabusController.TopicInput("Invalid",start.plusYears(1))));
        assertThrows(ResponseStatusException.class,()->controller.uploadFile(planId,new MockMultipartFile("file","fake.pdf","application/pdf",new byte[6])));
        assertDoesNotThrow(()->controller.uploadFile(planId,new MockMultipartFile("file","final.pdf","application/pdf",pdf)));
        assertArrayEquals(pdf,controller.download(planId,auth("TEACHER","THSYL")).getBody());
        auth("TEACHER","THSYL");
        assertThrows(AccessDeniedException.class,()->controller.addTopic(planId,new SyllabusController.TopicInput("Not allowed",today)));
    }

    @Test void classPdfIsIndependentOfSubjectsAndReadOnlyForTeachersAndStudents() throws Exception {
        var admin=auth("ADMIN","ADSYL");
        documents.upload("10","2026-27","FINAL",new MockMultipartFile("file","complete.pdf","application/pdf",pdf),admin);
        UUID documentId=(UUID)documents.list(admin).get(0).get("id");
        jdbc.update("insert into teachers(id,tenant_id,user_id,teacher_id,email,password_hash,display_name) values(?,?,'THENG','EMP-ENG','english@example.invalid','unused','English Teacher')",UUID.randomUUID(),tenant);
        jdbc.update("insert into teacher_workloads(id,tenant_id,teacher_user_id,teacher_name,teacher_role,subject,class_name,section_name,day_of_week,start_time,end_time,duration_minutes) values(?,?,'THENG','English Teacher','Subject teacher','English','10','B','Monday','10:00','11:00',60)",UUID.randomUUID(),tenant);
        var english=auth("TEACHER","THENG");
        assertEquals(1,documents.list(english).size());
        assertTrue(controller.list(english).isEmpty());
        assertArrayEquals(pdf,documents.download(documentId,english).getBody());
        assertThrows(AccessDeniedException.class,()->documents.upload("10","2026-27","FINAL",new MockMultipartFile("file","complete.pdf","application/pdf",pdf),english));
        jdbc.update("insert into students(id,tenant_id,admission_number,display_name,user_id,class_name,section_name) values(?,?,'STSYL','Test student','STSYL','Class 10','A')",UUID.randomUUID(),tenant);
        var student=auth("STUDENT","STSYL");
        assertEquals(1,documents.list(student).size());
        assertArrayEquals(pdf,documents.download(documentId,student).getBody());
        jdbc.update("update students set class_name='9' where tenant_id=? and user_id='STSYL'",tenant);
        assertTrue(documents.list(student).isEmpty());
        assertThrows(ResponseStatusException.class,()->documents.download(documentId,student));
        TenantContext.set(UUID.randomUUID());
        assertTrue(documents.list(auth("ADMIN","ADSYL")).isEmpty());
    }

    @Test void unrelatedTeacherCannotReadDownloadOrSubmit() {
        UUID id = create();
        UUID topic = (UUID) topics(controller.list(auth("ADMIN", "ADSYL")).get(0)).get(0).get("id");
        var stranger = auth("TEACHER", "OTHER");
        assertTrue(controller.list(stranger).isEmpty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> controller.download(id, stranger)).getStatusCode());
        assertThrows(ResponseStatusException.class, () -> controller.addNote(topic, new SyllabusController.NoteInput("Not my class"), stranger));
        assertThrows(ResponseStatusException.class, () -> controller.complete(topic, new SyllabusController.Completion(true), stranger));
        assertNull(jdbc.queryForMap("select completed_at from syllabus_topics where id = ?", topic).get("completed_at"));
    }

    @Test void tenantBoundaryAlsoAppliesToAdmins() {
        UUID id = create();
        var admin = auth("ADMIN", "ADSYL");
        UUID topic = (UUID) topics(controller.list(admin).get(0)).get(0).get("id");
        TenantContext.set(UUID.randomUUID());
        assertTrue(controller.list(admin).isEmpty());
        assertThrows(ResponseStatusException.class, () -> controller.download(id, admin));
        assertThrows(ResponseStatusException.class, () -> controller.updateTopic(topic, new SyllabusController.TopicInput("Changed", today), admin));
    }

    @Test void duplicateCompletionCannotOverwriteOriginalSubmissionAndCompletedDeadlineIsLocked() {
        create();
        var teacher = auth("TEACHER", "THSYL");
        UUID id = (UUID) topics(controller.list(teacher).get(0)).get(0).get("id");
        controller.complete(id, new SyllabusController.Completion(true), teacher);
        Object original = jdbc.queryForMap("select completed_at from syllabus_topics where id = ?", id).get("completed_at");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> controller.complete(id, new SyllabusController.Completion(true), teacher)).getStatusCode());
        assertEquals(original, jdbc.queryForMap("select completed_at from syllabus_topics where id = ?", id).get("completed_at"));
        var admin = auth("ADMIN", "ADSYL");
        assertThrows(ResponseStatusException.class, () -> controller.updateTopic(id, new SyllabusController.TopicInput("Changed", today.plusDays(1)), admin));
    }

    @Test void adminCanMaintainPendingDeadlinesAndTeacherCannotEditOrCreateSyllabus() {
        create();
        var admin = auth("ADMIN", "ADSYL");
        UUID id = (UUID) topics(controller.list(admin).get(0)).get(1).get("id");
        controller.updateTopic(id, new SyllabusController.TopicInput("Geometry revised", today.plusDays(3)), admin);
        var teacher = auth("TEACHER", "THSYL");
        var topic = topics(controller.list(teacher).get(0)).get(1);
        assertEquals("Geometry revised", topic.get("title"));
        assertEquals("PENDING", topic.get("status"));
        assertThrows(AccessDeniedException.class, () -> controller.updateTopic(id, new SyllabusController.TopicInput("Tampered", today), teacher));
        assertThrows(AccessDeniedException.class, () -> controller.create(entry("A", "Mathematics"), null, teacher));
        auth("STUDENT", "STSYL");
        assertTrue(controller.list(auth("STUDENT", "STSYL")).isEmpty());
    }


    @Test void excelImportPreviewConfirmAndStudentIsolation() throws Exception {
        var admin = auth("ADMIN", "ADSYL");
        byte[] bytes;
        int year = today.getMonthValue() < 4 ? today.getYear()-1 : today.getYear();
        try(var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(excel.template().getBody()));
            var out = new java.io.ByteArrayOutputStream()) {
            var row = book.getSheet("Class 10").createRow(1);
            String[] values = {year+"-"+String.format("%02d", (year+1)%100), "A", "MATH", "Mathematics", "Algebra", "Equations", "Practice", "EMP-SYL", LocalDate.of(year,4,1).toString(), today.toString(), "HIGH"};
            for(int c=0;c<values.length;c++) row.createCell(c).setCellValue(values[c]);
            book.write(out); bytes=out.toByteArray();
        }
        var file = new MockMultipartFile("file", "syllabus.xlsx", "application/octet-stream", bytes);
        var preview = excel.previewForPeriod(file, admin, "FINAL");
        assertTrue(((List<?>)preview.get("errors")).isEmpty());
        assertTrue(controller.list(admin).isEmpty());
        UUID previewId=(UUID)preview.get("previewId");
        assertThrows(ResponseStatusException.class, () -> excel.confirm(previewId,auth("ADMIN","OTHER")));
        admin=auth("ADMIN","ADSYL");
        assertEquals(1,excel.confirm(previewId,admin).get("topics"));
        assertEquals("FINAL",controller.list(admin).get(0).get("period"));
        final var confirmingAdmin=admin;
        assertThrows(ResponseStatusException.class, () -> excel.confirm(previewId,confirmingAdmin));
        assertFalse(((List<?>)excel.preview(file,admin).get("errors")).isEmpty());
        var teacher=auth("TEACHER","THSYL");
        UUID topicId=(UUID)topics(controller.list(teacher).get(0)).get(0).get("id");
        controller.complete(topicId,new SyllabusController.Completion(true),teacher);
        assertFalse(topics(controller.list(teacher).get(0)).get(0).containsKey("points"));
        assertThrows(ResponseStatusException.class,()->controller.complete(topicId,new SyllabusController.Completion(true),teacher));
        jdbc.update("insert into students(id,tenant_id,admission_number,display_name,user_id,class_name,section_name) values(?,?,'STSYL','Test student','STSYL','Class 10','A')",UUID.randomUUID(),tenant);
        assertEquals(1,controller.list(auth("STUDENT","STSYL")).size());
        jdbc.update("update students set section_name='B' where tenant_id=? and user_id='STSYL'",tenant);
        assertTrue(controller.list(auth("STUDENT","STSYL")).isEmpty());
        assertThrows(AccessDeniedException.class,()->excel.preview(file,auth("TEACHER","THSYL")));
        TenantContext.set(UUID.randomUUID());
        assertTrue(controller.list(auth("ADMIN","ADSYL")).isEmpty());
    }

    @Test void rejectsUnassignedClassesAndInvalidDocuments() {
        var admin = auth("ADMIN", "ADSYL");
        assertThrows(ResponseStatusException.class, () -> controller.create(entry("B", "Mathematics"), null, admin));
        assertThrows(ResponseStatusException.class, () -> controller.create(entry("A", "English"), null, admin));
        assertThrows(ResponseStatusException.class, () -> controller.create(entry("A", "Mathematics"), new MockMultipartFile("file", "fake.pdf", "application/pdf", "not a PDF".getBytes(StandardCharsets.UTF_8)), admin));
        assertThrows(ResponseStatusException.class, () -> controller.create(entry("A", "Mathematics"), new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[5 * 1024 * 1024 + 1]), admin));
        assertTrue(controller.list(admin).isEmpty());
    }

    @Test void accessFollowsCurrentActiveWorkload() {
        create();
        var teacher = auth("TEACHER", "THSYL");
        assertEquals(1, controller.list(teacher).size());
        jdbc.update("update teacher_workloads set subject = 'English' where tenant_id = ?", tenant);
        assertTrue(controller.list(teacher).isEmpty());
        jdbc.update("update teacher_workloads set subject = 'Mathematics', section_name = 'B' where tenant_id = ?", tenant);
        assertTrue(controller.list(teacher).isEmpty());
        jdbc.update("update teacher_workloads set section_name = 'A', status = 'INACTIVE' where tenant_id = ?", tenant);
        assertTrue(controller.list(teacher).isEmpty());
    }

    @Test void rejectsBlankNotesMissingDeadlineAndUncheckedCompletion() {
        assertFalse(validator.validate(new SyllabusController.NoteInput("  ")).isEmpty());
        assertFalse(validator.validate(new SyllabusController.TopicInput("Topic", null)).isEmpty());
        assertFalse(validator.validate(new SyllabusController.Completion(false)).isEmpty());
        assertFalse(validator.validate(new SyllabusController.Completion(null)).isEmpty());
        assertFalse(validator.validate(new SyllabusController.CreateSyllabus("Plan", "10", "A", "Math", List.of())).isEmpty());
    }

    @Test void httpUploadAndTeacherReadSerializeTopicsAndRejectUncheckedSubmission() throws Exception {
        var admin = jwt().jwt(j -> j.subject("ADSYL").claim("tenant_id", tenant.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var teacher = jwt().jwt(j -> j.subject("THSYL").claim("tenant_id", tenant.toString())).authorities(new SimpleGrantedAuthority("ROLE_TEACHER"));
        var entry = new MockMultipartFile("syllabus", "", "application/json", """
            {"title":"HTTP syllabus","className":"10","sectionName":"A","subject":"Mathematics",
             "topics":[{"title":"Algebra","deadline":"2026-12-01"}]}
            """.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/syllabuses").file(entry).file(new MockMultipartFile("file", "syllabus.pdf", "application/pdf", pdf)).with(admin))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").exists());
        mvc.perform(get("/api/v1/syllabuses").with(teacher)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("HTTP syllabus"))
            .andExpect(jsonPath("$[0].topics[0].deadline").value("2026-12-01"))
            .andExpect(jsonPath("$[0].topics[0].notes").isArray());
        UUID topic = jdbc.queryForObject("select t.id from syllabus_topics t join syllabuses s on s.id = t.syllabus_id where s.tenant_id = ?", UUID.class, tenant);
        mvc.perform(post("/api/v1/syllabuses/topics/" + topic + "/complete").with(teacher).contentType("application/json").content("{\"completed\":false}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/syllabuses/topics/" + topic + "/notes").with(teacher).contentType("application/json").content("{\"body\":\"   \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/syllabuses/topics/" + topic + "/complete").with(teacher).contentType("application/json").content("{\"completed\":true}"))
            .andExpect(status().isOk());
    }

    @Test void annualPlanPersistsTwelveMonthlyTargetsForEverySubject() {
        jdbc.update("""
            insert into teacher_workloads(id, tenant_id, teacher_user_id, teacher_name, teacher_role, subject, class_name, section_name, day_of_week, start_time, end_time, duration_minutes)
            values (?, ?, 'THSYL', 'Syllabus Teacher', 'Subject teacher', 'Science', '10', 'A', 'Monday', '11:00', '12:00', 60)
            """, UUID.randomUUID(), tenant);
        LocalDate year = LocalDate.of(2026, 4, 1);
        var targets = java.util.stream.IntStream.range(0, 12).mapToObj(month ->
            new SyllabusController.TopicInput("Monthly target " + month, year.plusMonths(month).withDayOfMonth(20), LocalTime.of(16, 0))).toList();
        var annual = new SyllabusController.AnnualSyllabus("Annual plan", "10", "A", year,
            List.of(new SyllabusController.SubjectTargets("Mathematics", targets), new SyllabusController.SubjectTargets("Science", targets)));
        assertEquals(2, controller.createAnnual(annual, new MockMultipartFile("file", "annual.pdf", "application/pdf", pdf), auth("ADMIN", "ADSYL")).get("subjects"));
        var teacher = auth("TEACHER", "THSYL");
        var plans = controller.list(teacher);
        assertEquals(2, plans.size());
        for (var plan : plans) {
            assertEquals("2026-04-01", plan.get("year_start").toString());
            assertEquals(12, topics(plan).size());
            assertEquals("2027-03-20", topics(plan).get(11).get("deadline").toString());
            assertEquals("16:00:00", topics(plan).get(0).get("deadline_time").toString());
            assertArrayEquals(pdf, controller.download((UUID) plan.get("id"), teacher).getBody());
        }
        UUID topic = (UUID) topics(plans.get(0)).get(0).get("id");
        var admin = auth("ADMIN", "ADSYL");
        assertThrows(ResponseStatusException.class, () -> controller.updateTopic(topic, new SyllabusController.TopicInput("Moved", year.plusYears(1)), admin));
    }

    @Test void annualPlanRejectsOutOfYearTargetsDuplicateSubjectsAndMidMonthStart() {
        var admin = auth("ADMIN", "ADSYL");
        LocalDate year = LocalDate.of(2026, 4, 1);
        var outside = new SyllabusController.SubjectTargets("Mathematics", List.of(new SyllabusController.TopicInput("Outside", year.minusDays(1))));
        assertThrows(ResponseStatusException.class, () -> controller.createAnnual(new SyllabusController.AnnualSyllabus("Annual", "10", "A", year, List.of(outside)), null, admin));
        var valid = new SyllabusController.SubjectTargets("Mathematics", List.of(new SyllabusController.TopicInput("Inside", year)));
        assertThrows(ResponseStatusException.class, () -> controller.createAnnual(new SyllabusController.AnnualSyllabus("Annual", "10", "A", year, List.of(valid, valid)), null, admin));
        assertThrows(ResponseStatusException.class, () -> controller.createAnnual(new SyllabusController.AnnualSyllabus("Annual", "10", "A", year.plusDays(1), List.of(valid)), null, admin));
        assertTrue(controller.list(admin).isEmpty());
    }

    @Test void completionUsesDeadlineTimeWithinTheSameDay() {
        create();
        var admin = auth("ADMIN", "ADSYL");
        var topics = topics(controller.list(admin).get(0));
        UUID first = (UUID) topics.get(0).get("id"), second = (UUID) topics.get(1).get("id");
        for (UUID id : List.of(first, second)) controller.updateTopic(id, new SyllabusController.TopicInput("Timed target", today, LocalTime.of(11, 0)), admin);
        jdbc.update("update syllabus_topics set completed_by = 'THSYL', completed_at = (deadline + time '10:59') at time zone 'Asia/Kolkata' where id = ?", first);
        jdbc.update("update syllabus_topics set completed_by = 'THSYL', completed_at = (deadline + time '11:01') at time zone 'Asia/Kolkata' where id = ?", second);
        var tracked = topics(controller.list(admin).get(0));
        assertEquals("COMPLETED", tracked.get(0).get("status"));
        assertEquals("COMPLETED_LATE", tracked.get(1).get("status"));
    }

    @Test void termPlansPersistAndEnforceTheirOwnSixMonthWindow() {
        LocalDate year = LocalDate.of(2026, 4, 1);
        var admin = auth("ADMIN", "ADSYL");
        for (String period : List.of("TERM_1", "TERM_2")) {
            LocalDate due = year.plusMonths(period.equals("TERM_1") ? 5 : 11).withDayOfMonth(20);
            var targets = List.of(new SyllabusController.SubjectTargets("Mathematics", List.of(new SyllabusController.TopicInput("Term target", due))));
            controller.createAnnual(new SyllabusController.AnnualSyllabus(period, "10", "A", year, targets, period), null, admin);
        }
        var plans = controller.list(auth("TEACHER", "THSYL"));
        assertEquals(2, plans.size());
        assertEquals(Set.of("TERM_1", "TERM_2"), plans.stream().map(p -> p.get("period")).collect(java.util.stream.Collectors.toSet()));
        auth("ADMIN", "ADSYL");
        UUID termOneTopic = (UUID) topics(plans.stream().filter(p -> p.get("period").equals("TERM_1")).findFirst().orElseThrow()).get(0).get("id");
        assertThrows(ResponseStatusException.class, () -> controller.updateTopic(termOneTopic, new SyllabusController.TopicInput("Wrong term", year.plusMonths(6)), admin));
        var wrongTargets = List.of(new SyllabusController.SubjectTargets("Mathematics", List.of(new SyllabusController.TopicInput("Wrong term", year))));
        assertThrows(ResponseStatusException.class, () -> controller.createAnnual(new SyllabusController.AnnualSyllabus("Wrong", "10", "A", year, wrongTargets, "TERM_2"), null, admin));
    }

    @Test void pdfSubjectExtractionIsAdminOnlyAndScopedToClass() throws Exception {
        var pdf = SyllabusPdfExtractorTest.pdf("TERM 1: Maths, English");
        var admin = jwt().jwt(j -> j.subject("ADSYL").claim("tenant_id", tenant.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var teacher = jwt().jwt(j -> j.subject("THSYL").claim("tenant_id", tenant.toString())).authorities(new SimpleGrantedAuthority("ROLE_TEACHER"));
        mvc.perform(multipart("/api/v1/syllabuses/extract-subjects").file(pdf).param("className", "10").param("sectionName", "A").with(admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.subjects[0]").value("Mathematics")).andExpect(jsonPath("$.subjects.length()").value(1));
        mvc.perform(multipart("/api/v1/syllabuses/extract-subjects").file(pdf).param("className", "10").param("sectionName", "B").with(admin))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/syllabuses/extract-subjects").file(pdf).param("className", "10").param("sectionName", "A").with(teacher))
            .andExpect(status().isForbidden());
    }
}
