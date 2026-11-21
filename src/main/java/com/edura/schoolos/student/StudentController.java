package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {
    private final JdbcTemplate jdbc;
    public StudentController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN', 'TEACHER')")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "") String q, Authentication authentication) {
        var tenant = TenantContext.require();
        String search = "%" + q + "%";
        boolean teacherOnly = authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_TEACHER".equals(authority.getAuthority()));
        String assignedClassFilter = teacherOnly ? """
            and exists (select 1 from teacher_workloads workload
                where workload.tenant_id = s.tenant_id and workload.teacher_user_id = ? and workload.status = 'ACTIVE'
                  and lower(trim(workload.teacher_role)) = 'class teacher'
                  and lower(workload.class_name) = lower(regexp_replace(coalesce(s.class_name, c.name), '^class\\s*', '', 'i'))
                  and lower(workload.section_name) = lower(coalesce(s.section_name, sec.name)))
            """ : "";
        Object[] parameters = teacherOnly
            ? new Object[]{tenant, authentication.getName(), search, search, search, search, search, search, search}
            : new Object[]{tenant, search, search, search, search, search, search, search};
        return jdbc.queryForList("""
            select coalesce((select sum(d.points) from student_discipline d where d.tenant_id = s.tenant_id and d.student_id = s.id and d.status = 'APPROVED'), 0) as discipline_points, s.id, s.user_id, s.display_name, s.email, s.roll_number, s.registration_type,
                   coalesce(s.class_name, c.name) as class_name, coalesce(s.section_name, sec.name) as section_name, s.parent_contact_number,
                   coalesce((select sum(p.amount) from payments p join invoices i on i.id = p.invoice_id and i.tenant_id = p.tenant_id where i.tenant_id = s.tenant_id and i.student_id = s.id and i.month_start is not null and p.status = 'SUCCESS'), 0) as paid_fee_amount,
                   coalesce((select sum(greatest(i.amount - coalesce((select sum(p.amount) from payments p where p.invoice_id = i.id and p.tenant_id = i.tenant_id and p.status = 'SUCCESS'), 0), 0)) from invoices i where i.tenant_id = s.tenant_id and i.student_id = s.id and i.month_start is not null), 0) as pending_fee_amount,
                   (select count(*) from invoices i where i.tenant_id = s.tenant_id and i.student_id = s.id and i.month_start is not null) as monthly_fee_count
            from students s left join sections sec on sec.id = s.section_id left join classes c on c.id = sec.class_id
            where s.tenant_id = ? and s.active = true %s and (lower(s.display_name) like lower(?) or lower(s.email) like lower(?)
                or lower(s.user_id) like lower(?) or lower(coalesce(s.roll_number, '')) like lower(?)
                or lower(coalesce(s.parent_contact_number, '')) like lower(?)
                or lower(coalesce(s.class_name, '')) like lower(?) or lower(coalesce(s.section_name, '')) like lower(?))
            order by s.display_name
            """.formatted(assignedClassFilter), parameters);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody CreateStudent request) {
        var tenant = TenantContext.require();
        Boolean duplicate = jdbc.queryForObject("""
            select exists(select 1 from students where tenant_id = ? and active = true and (
                lower(trim(admission_number)) = lower(trim(?))
                or (email is not null and lower(trim(email)) = lower(trim(coalesce(?, ''))))
                or (lower(trim(coalesce(roll_number, ''))) = lower(trim(?))
                    and lower(trim(coalesce(class_name, ''))) = lower(trim(coalesce(?, '')))
                    and lower(trim(coalesce(section_name, ''))) = lower(trim(coalesce(?, ''))))
                or (date_of_birth = ? and lower(trim(display_name)) = lower(trim(?))
                    and lower(trim(coalesce(class_name, ''))) = lower(trim(coalesce(?, '')))
                    and lower(trim(coalesce(section_name, ''))) = lower(trim(coalesce(?, ''))))
            ))
            """, Boolean.class, tenant, request.admissionNumber(), request.email(), request.rollNumber(), request.className(), request.sectionName(), request.dateOfBirth(), request.displayName(), request.className(), request.sectionName());
        if (Boolean.TRUE.equals(duplicate)) throw new ResponseStatusException(HttpStatus.CONFLICT, "A student with this admission number, email, or class roll number already exists.");
        var id = UUID.randomUUID();
        UUID sectionId = request.sectionId();
        if (sectionId == null && request.className() != null && request.sectionName() != null) {
            UUID classId = jdbc.query("select id from classes where tenant_id = ? and name = ? limit 1", rs -> rs.next() ? rs.getObject("id", UUID.class) : null, tenant, request.className());
            if (classId == null) {
                classId = UUID.randomUUID();
                jdbc.update("insert into classes(id, tenant_id, name) values (?, ?, ?)", classId, tenant, request.className());
            }
            UUID resolvedClassId = classId;
            sectionId = jdbc.query("select id from sections where tenant_id = ? and class_id = ? and name = ? limit 1", rs -> rs.next() ? rs.getObject("id", UUID.class) : null, tenant, resolvedClassId, request.sectionName());
            if (sectionId == null) {
                sectionId = UUID.randomUUID();
                jdbc.update("insert into sections(id, tenant_id, class_id, name) values (?, ?, ?, ?)", sectionId, tenant, resolvedClassId, request.sectionName());
            }
        }
        if (request.transportEnabled() && !java.util.Set.of("0-5", "5-10", "10-15", "15-20", "20-above").contains(request.transportDistanceBand())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a transport distance band");
        String registrationType = request.registrationType() == null || request.registrationType().isBlank() ? "OLD" : request.registrationType().toUpperCase();
        if (!java.util.Set.of("OLD", "NEW").contains(registrationType)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registration type must be Old or New");
        jdbc.update("insert into students(id, tenant_id, admission_number, display_name, email, user_id, roll_number, class_name, section_name, section_id, date_of_birth, transport_enabled, transport_distance_band, registration_type) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, tenant, request.admissionNumber(), request.displayName(), request.email(), request.admissionNumber(), request.rollNumber(), request.className(), request.sectionName(), sectionId, request.dateOfBirth(), request.transportEnabled(), request.transportEnabled() ? request.transportDistanceBand() : null, registrationType);
        return Map.of("id", id, "admissionNumber", request.admissionNumber(), "displayName", request.displayName(), "registrationType", registrationType);
    }

    public record CreateStudent(@NotBlank String admissionNumber, @NotBlank String displayName, String email, @NotBlank String rollNumber, String className, String sectionName, UUID sectionId, java.time.LocalDate dateOfBirth, boolean transportEnabled, String transportDistanceBand, String registrationType) {}
}
