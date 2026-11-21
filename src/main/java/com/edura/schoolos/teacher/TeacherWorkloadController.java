package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/teacher-workloads")
public class TeacherWorkloadController {
    private static final Set<String> VALID_DAYS = Set.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday");
    private final JdbcTemplate jdbc;

    public TeacherWorkloadController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER', 'STUDENT')")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "") String q, Authentication authentication) {
        var tenant = TenantContext.require();
        var search = "%" + q + "%";
        boolean teacherOnly = authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_TEACHER".equals(authority.getAuthority()));
        boolean studentOnly = authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_STUDENT".equals(authority.getAuthority()));
        String scheduleFilter = " ";
        Object[] params = new Object[]{tenant, search, search, search};
        if (teacherOnly) {
            scheduleFilter = " and teacher_user_id = ? ";
            params = new Object[]{tenant, authentication.getName(), search, search, search};
        } else if (studentOnly) {
            var studentClass = jdbc.query("select class_name, section_name from students where tenant_id = ? and user_id = ? and active", rs -> rs.next()
                ? new String[]{rs.getString("class_name"), rs.getString("section_name")} : null, tenant, authentication.getName());
            if (studentClass == null || studentClass[0] == null || studentClass[1] == null) {
                return List.of();
            }
            String classNumber = studentClass[0].replaceFirst("(?i)^class\\s*", "").trim();
            scheduleFilter = " and class_name = ? and section_name = ? ";
            params = new Object[]{tenant, classNumber, studentClass[1], search, search, search};
        }
        return jdbc.queryForList("""
            select id, teacher_user_id, teacher_name, teacher_role, subject, class_name, section_name, day_of_week,
                   start_time, end_time, duration_minutes, status, created_at,
                   (select a.status from teacher_adjustments a where a.tenant_id=teacher_workloads.tenant_id
                     and a.workload_id=teacher_workloads.id and a.status='APPROVED'
                     and a.replacement_user_id=teacher_workloads.teacher_user_id
                     order by a.reviewed_at desc limit 1) as adjustment_status
            from teacher_workloads
            where tenant_id = ? %s and (lower(teacher_name) like lower(?) or lower(subject) like lower(?)
                or lower(class_name || '-' || section_name) like lower(?))
            order by start_time, teacher_name, class_name, section_name
            """.formatted(scheduleFilter), params);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody CreateWorkload request) {
        var tenant = TenantContext.require();
        return createWorkload(request, tenant);
    }

    @PostMapping("/weekly")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<Map<String, Object>> createWeekly(@Valid @RequestBody CreateWeeklyWorkload request) {
        var tenant = TenantContext.require();
        return List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday").stream()
            .map(day -> createWorkload(new CreateWorkload(
                request.workload().teacherName(), request.workload().teacherUserId(), request.workload().teacherRole(),
                request.workload().subject(), request.workload().className(), request.workload().sectionName(), day,
                request.workload().startTime(), request.workload().endTime(), request.workload().durationMinutes()), tenant))
            .toList();
    }

    private Map<String, Object> createWorkload(CreateWorkload request, UUID tenant) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new IllegalArgumentException("End time must be after start time");
        }
        validateSchoolSlot(request);
        validateDay(request.dayOfWeek());
        requireTeacher(request.teacherUserId(), tenant);
        validateClassTeacherAssignment(request, tenant, null);
        validateClassSchedule(request, tenant, null);
        ensureTeacherAvailable(request, tenant, null);
        var id = UUID.randomUUID();
        jdbc.update("""
            insert into teacher_workloads
              (id, tenant_id, teacher_user_id, teacher_name, teacher_role, subject, class_name, section_name, day_of_week,
               start_time, end_time, duration_minutes)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, tenant, request.teacherUserId(), request.teacherName(), request.teacherRole(), request.subject(),
            request.className(), request.sectionName(), request.dayOfWeek(), request.startTime(), request.endTime(), request.durationMinutes());
        return Map.ofEntries(
            Map.entry("id", id), Map.entry("teacherUserId", request.teacherUserId()), Map.entry("teacherName", request.teacherName()), Map.entry("teacherRole", request.teacherRole()),
            Map.entry("subject", request.subject()), Map.entry("className", request.className()), Map.entry("sectionName", request.sectionName()),
            Map.entry("dayOfWeek", request.dayOfWeek()), Map.entry("startTime", request.startTime()), Map.entry("endTime", request.endTime()),
            Map.entry("durationMinutes", request.durationMinutes()), Map.entry("status", "ACTIVE"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID id, @Valid @RequestBody CreateWorkload request) {
        var tenant = TenantContext.require();
        if (!request.endTime().isAfter(request.startTime())) {
            throw new IllegalArgumentException("End time must be after start time");
        }
        validateSchoolSlot(request);
        validateDay(request.dayOfWeek());
        requireTeacher(request.teacherUserId(), tenant);
        validateClassTeacherAssignment(request, tenant, id);
        validateClassSchedule(request, tenant, id);
        ensureTeacherAvailable(request, tenant, id);
        int updated = jdbc.update("""
            update teacher_workloads set teacher_user_id = ?, teacher_name = ?, teacher_role = ?, subject = ?,
              class_name = ?, section_name = ?, day_of_week = ?, start_time = ?, end_time = ?, duration_minutes = ?
            where id = ? and tenant_id = ?
            """, request.teacherUserId(), request.teacherName(), request.teacherRole(), request.subject(), request.className(),
            request.sectionName(), request.dayOfWeek(), request.startTime(), request.endTime(), request.durationMinutes(), id, tenant);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher workload not found");
        }
        return Map.ofEntries(
            Map.entry("id", id), Map.entry("teacherUserId", request.teacherUserId()), Map.entry("teacherName", request.teacherName()), Map.entry("teacherRole", request.teacherRole()),
            Map.entry("subject", request.subject()), Map.entry("className", request.className()), Map.entry("sectionName", request.sectionName()),
            Map.entry("dayOfWeek", request.dayOfWeek()), Map.entry("startTime", request.startTime()), Map.entry("endTime", request.endTime()),
            Map.entry("durationMinutes", request.durationMinutes()), Map.entry("status", "ACTIVE"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        var tenant = TenantContext.require();
        int deleted = jdbc.update("delete from teacher_workloads where id = ? and tenant_id = ?", id, tenant);
        if (deleted == 0) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher workload not found");
        }
    }

    private void validateDay(String day) {
        if (!VALID_DAYS.contains(day)) {
            throw new IllegalArgumentException("Day must be Monday through Saturday");
        }
    }

    private void validateSchoolSlot(CreateWorkload request) {
        var validStartTimes = Set.of(
            LocalTime.of(10, 0), LocalTime.of(11, 0), LocalTime.of(12, 0),
            LocalTime.of(14, 0), LocalTime.of(15, 0)
        );
        if (request.durationMinutes() != 60 || !request.endTime().equals(request.startTime().plusHours(1))
            || !validStartTimes.contains(request.startTime())) {
            throw new IllegalArgumentException("Classes must be one hour between 10 AM and 4 PM; 1 PM to 2 PM is the lunch break");
        }
    }

    private void ensureTeacherAvailable(CreateWorkload request, UUID tenant, UUID excludedWorkloadId) {
        String excludeCurrentWorkload = excludedWorkloadId == null ? "" : " and id <> ?";
        Object[] parameters = excludedWorkloadId == null
            ? new Object[]{tenant, request.teacherUserId(), request.dayOfWeek(), request.endTime(), request.startTime()}
            : new Object[]{tenant, request.teacherUserId(), request.dayOfWeek(), request.endTime(), request.startTime(), excludedWorkloadId};
        Integer conflicts = jdbc.queryForObject(("""
            select count(*) from teacher_workloads
            where tenant_id = ? and teacher_user_id = ? and day_of_week = ?
              and start_time < ? and end_time > ?
            """ + excludeCurrentWorkload), Integer.class, parameters);
        if (conflicts != null && conflicts > 0) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                "This teacher is already assigned to another class during this time slot");
        }
    }

    private void validateClassSchedule(CreateWorkload request, UUID tenant, UUID excludedWorkloadId) {
        String excludeCurrentWorkload = excludedWorkloadId == null ? "" : " and id <> ?";
        Object[] subjectParameters = excludedWorkloadId == null
            ? new Object[]{tenant, request.className(), request.sectionName(), request.subject(), request.dayOfWeek()}
            : new Object[]{tenant, request.className(), request.sectionName(), request.subject(), request.dayOfWeek(), excludedWorkloadId};
        Integer subjectConflicts = jdbc.queryForObject(("""
            select count(*) from teacher_workloads
            where tenant_id = ? and class_name = ? and section_name = ? and lower(subject) = lower(?) and day_of_week = ?
            """ + excludeCurrentWorkload), Integer.class, subjectParameters);
        if (subjectConflicts != null && subjectConflicts > 0) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                request.subject() + " is already assigned to Class " + request.className() + " - "
                    + request.sectionName() + " on " + request.dayOfWeek() + ". Choose another day.");
        }

        Object[] slotParameters = excludedWorkloadId == null
            ? new Object[]{tenant, request.className(), request.sectionName(), request.dayOfWeek(), request.endTime(), request.startTime()}
            : new Object[]{tenant, request.className(), request.sectionName(), request.dayOfWeek(), request.endTime(), request.startTime(), excludedWorkloadId};
        Integer slotConflicts = jdbc.queryForObject(("""
            select count(*) from teacher_workloads
            where tenant_id = ? and class_name = ? and section_name = ? and day_of_week = ?
              and start_time < ? and end_time > ?
            """ + excludeCurrentWorkload), Integer.class, slotParameters);
        if (slotConflicts != null && slotConflicts > 0) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                "Class " + request.className() + " - " + request.sectionName()
                    + " already has a class during this time slot. Choose another time.");
        }
    }

    private void validateClassTeacherAssignment(CreateWorkload request, UUID tenant, UUID excludedWorkloadId) {
        if (!"class teacher".equalsIgnoreCase(request.teacherRole().trim())) {
            return;
        }
        String excludeCurrentWorkload = excludedWorkloadId == null ? "" : " and id <> ?";
        Object[] teacherParameters = excludedWorkloadId == null
            ? new Object[]{tenant, request.teacherUserId()}
            : new Object[]{tenant, request.teacherUserId(), excludedWorkloadId};
        List<Map<String, Object>> teacherAssignments = jdbc.queryForList("""
            select class_name, section_name from teacher_workloads
            where tenant_id = ? and teacher_user_id = ? and lower(teacher_role) = 'class teacher'
            """ + excludeCurrentWorkload, teacherParameters);
        Map<String, Object> differentTeacherClass = teacherAssignments.stream()
            .filter(assignment -> !request.className().equals(assignment.get("class_name"))
                || !request.sectionName().equals(assignment.get("section_name")))
            .findFirst().orElse(null);
        if (differentTeacherClass != null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                request.teacherName() + " is already assigned as Class teacher for Class "
                    + differentTeacherClass.get("class_name") + " - " + differentTeacherClass.get("section_name"));
        }

        Object[] classParameters = excludedWorkloadId == null
            ? new Object[]{tenant, request.className(), request.sectionName()}
            : new Object[]{tenant, request.className(), request.sectionName(), excludedWorkloadId};
        List<Map<String, Object>> classAssignments = jdbc.queryForList("""
            select teacher_name, teacher_user_id from teacher_workloads
            where tenant_id = ? and class_name = ? and section_name = ? and lower(teacher_role) = 'class teacher'
            """ + excludeCurrentWorkload, classParameters);
        Map<String, Object> differentClassTeacher = classAssignments.stream()
            .filter(assignment -> !request.teacherUserId().equals(assignment.get("teacher_user_id")))
            .findFirst().orElse(null);
        if (differentClassTeacher != null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                "Class " + request.className() + " - " + request.sectionName()
                    + " already has a Class teacher: " + differentClassTeacher.get("teacher_name"));
        }
    }

    private void requireTeacher(String teacherUserId, UUID tenant) {
        if (teacherUserId == null || jdbc.queryForObject(
            "select count(*) from teachers where tenant_id = ? and user_id = ? and active",
            Integer.class, tenant, teacherUserId) != 1) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an active teacher");
        }
    }

    public record CreateWorkload(
        @NotBlank String teacherName,
        @NotBlank String teacherUserId,
        @NotBlank String teacherRole,
        @NotBlank String subject,
        @NotBlank String className,
        @NotBlank String sectionName,
        @NotBlank String dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull Integer durationMinutes
    ) {}

    public record CreateWeeklyWorkload(@NotNull @Valid CreateWorkload workload) {}
}
