package com.edura.schoolos.dashboard;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/academics")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAcademicsController {
    private final JdbcTemplate jdbc;
    public AdminAcademicsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> options() {
        UUID tenant = TenantContext.require();
        return Map.of("years", jdbc.queryForList("select id, name, starts_on, ends_on, active from academic_years where tenant_id = ? order by starts_on desc", tenant),
            "exams", jdbc.queryForList("select id, name, academic_year_id, exam_date from exams where tenant_id = ? order by name", tenant),
            "subjects", jdbc.queryForList("select id, name from subjects where tenant_id = ? order by name", tenant));
    }
    private UUID lockTenant() {
        UUID tenant = TenantContext.require();
        jdbc.queryForObject("select id from tenants where id = ? for update", UUID.class, tenant);
        return tenant;
    }
    @PostMapping("/years")
    @Transactional
    public Map<String, Object> saveYear(@Valid @RequestBody Year request) {
        UUID tenant = lockTenant();
        int startYear = Integer.parseInt(request.name().substring(0, 4));
        if (!request.endsOn().isAfter(request.startsOn()) || request.startsOn().getYear() != startYear
            || request.endsOn().getYear() != startYear + 1 || !request.name().endsWith(String.format("%02d", (startYear + 1) % 100)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a consecutive YYYY-YY academic year and dates within those years");
        var existing = jdbc.queryForList("select id, starts_on, ends_on from academic_years where tenant_id = ? and name = ?", tenant, request.name());
        if (!existing.isEmpty() && (!existing.get(0).get("starts_on").toString().equals(request.startsOn().toString()) || !existing.get(0).get("ends_on").toString().equals(request.endsOn().toString())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This year already exists with different dates");
        if (request.active()) jdbc.update("update academic_years set active = false where tenant_id = ?", tenant);
        UUID id = existing.isEmpty() ? UUID.randomUUID() : (UUID) existing.get(0).get("id");
        if (existing.isEmpty()) jdbc.update("insert into academic_years(id, tenant_id, name, starts_on, ends_on, active) values (?, ?, ?, ?, ?, ?)", id, tenant, request.name(), request.startsOn(), request.endsOn(), request.active());
        else jdbc.update("update academic_years set active = ? where id = ? and tenant_id = ?", request.active(), id, tenant);
        return Map.of("id", id);
    }
    @PostMapping("/subjects")
    @Transactional
    public Map<String, Object> createSubject(@Valid @RequestBody Name request) {
        UUID tenant = lockTenant();
        var existing = jdbc.queryForList("select id from subjects where tenant_id = ? and lower(trim(name)) = lower(?)", UUID.class, tenant, request.name().trim());
        if (!existing.isEmpty()) return Map.of("id", existing.get(0));
        UUID id = UUID.randomUUID();
        jdbc.update("insert into subjects(id, tenant_id, name) values (?, ?, ?)", id, tenant, request.name().trim());
        return Map.of("id", id);
    }
    @PostMapping("/exams")
    @Transactional
    public Map<String, Object> createExam(@Valid @RequestBody Exam request) {
        UUID tenant = lockTenant();
        if (jdbc.queryForObject("select count(*) from academic_years where id = ? and tenant_id = ?", Integer.class, request.academicYearId(), tenant) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an academic year from your school");
        if (jdbc.queryForObject("select count(*) from exams where tenant_id = ? and academic_year_id = ? and lower(name) = lower(?)", Integer.class, tenant, request.academicYearId(), request.name().trim()) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This exam already exists for the selected year");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into exams(id, tenant_id, name, academic_year_id, exam_date) values (?, ?, ?, ?, ?)", id, tenant, request.name().trim(), request.academicYearId(), request.examDate());
        return Map.of("id", id);
    }
    @PostMapping("/results")
    @Transactional
    public Map<String, Object> createResult(@Valid @RequestBody Result request) { return saveResult(null, request); }

    @PutMapping("/results/{id}")
    @Transactional
    public Map<String, Object> updateResult(@PathVariable UUID id, @Valid @RequestBody Result request) { return saveResult(id, request); }

    private Map<String, Object> saveResult(UUID id, Result request) {
        UUID tenant = lockTenant();
        if (jdbc.queryForObject("select count(*) from students where id = ? and tenant_id = ? and active", Integer.class, request.studentId(), tenant) == 0
            || jdbc.queryForObject("select count(*) from exams where id = ? and tenant_id = ?", Integer.class, request.examId(), tenant) == 0
            || jdbc.queryForObject("select count(*) from subjects where id = ? and tenant_id = ?", Integer.class, request.subjectId(), tenant) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a student, exam and subject from your school");
        if (id != null && jdbc.queryForObject("select count(*) from results where id = ? and tenant_id = ?", Integer.class, id, tenant) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Result not found");
        if (jdbc.queryForObject("select count(*) from results where tenant_id = ? and student_id = ? and exam_id = ? and subject_id = ? and (?::uuid is null or id <> ?)", Integer.class,
            tenant, request.studentId(), request.examId(), request.subjectId(), id, id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A result already exists for this student, exam and subject. Edit that result instead.");
        UUID resultId = id == null ? UUID.randomUUID() : id;
        if (id == null) jdbc.update("insert into results(id, tenant_id, student_id, exam_id, subject_id, marks, grade) values (?, ?, ?, ?, ?, ?, ?)", resultId, tenant, request.studentId(), request.examId(), request.subjectId(), request.marks(), request.grade());
        else jdbc.update("update results set student_id = ?, exam_id = ?, subject_id = ?, marks = ?, grade = ? where tenant_id = ? and id = ?", request.studentId(), request.examId(), request.subjectId(), request.marks(), request.grade(), tenant, id);
        return Map.of("id", resultId);
    }
    public record Year(@NotNull @Pattern(regexp = "20[0-9]{2}-[0-9]{2}") String name, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn, boolean active) {}
    public record Name(@NotBlank @Size(max = 100) String name) {}
    public record Exam(@NotBlank @Size(max = 160) String name, @NotNull UUID academicYearId, @NotNull LocalDate examDate) {}
    public record Result(@NotNull UUID studentId, @NotNull UUID examId, @NotNull UUID subjectId,
                         @NotNull @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal marks, @Size(max = 8) String grade) {}
}
