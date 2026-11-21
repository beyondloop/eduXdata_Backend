package com.edura.schoolos.dashboard;

import com.edura.schoolos.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class AdminDataController {
    private final JdbcTemplate jdbc;
    public AdminDataController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/profile")
    public Map<String, Object> profile(Authentication authentication) {
        var rows = jdbc.queryForList("select user_id as \"userId\", display_name as \"displayName\", email, profile_image as \"profileImage\" from admins where tenant_id = ? and user_id = ? and active", TenantContext.require(), authentication.getName());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin account not found");
        return rows.get(0);
    }

    @PutMapping("/profile")
    public Map<String, Object> saveProfile(@Valid @RequestBody AdminProfile profile, Authentication authentication) {
        String photo = profile.profileImage();
        if (photo != null && !photo.matches("data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a PNG, JPEG or WebP image");
        try {
            int updated = jdbc.update("update admins set display_name = ?, email = ?, profile_image = ? where tenant_id = ? and user_id = ? and active",
                profile.displayName().trim(), profile.email().trim(), photo, TenantContext.require(), authentication.getName());
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin account not found");
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This email is already registered");
        }
        return profile(authentication);
    }

    public record AdminProfile(@NotBlank @Size(max = 160) String displayName,
                               @NotBlank @Email @Size(max = 180) String email,
                               @Size(max = 2800000) String profileImage) {}

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> uploadDocument(@RequestParam(required = false) UUID studentId, @RequestParam MultipartFile file,
                                            Authentication authentication) throws IOException {
        UUID tenant = TenantContext.require();
        if (studentId != null && jdbc.queryForObject("select count(*) from students where id = ? and tenant_id = ?", Integer.class, studentId, tenant) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found");
        if (file.isEmpty() || file.getSize() > 15L * 1024 * 1024)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty file up to 15 MB");
        String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank() || name.length() > 180)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File name must contain 1 to 180 characters");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_documents(id, tenant_id, student_id, file_name, file_data, uploaded_by) values (?, ?, ?, ?, ?, ?)",
            id, tenant, studentId, name, file.getBytes(), authentication.getName());
        return Map.of("id", id);
    }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable UUID id) {
        var files = jdbc.queryForList("select file_name, file_data from admin_documents where tenant_id = ? and id = ?", TenantContext.require(), id);
        if (files.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", ContentDisposition.attachment().filename((String) files.get(0).get("file_name"), StandardCharsets.UTF_8).build().toString())
            .body((byte[]) files.get(0).get("file_data"));
    }

    private LocalDate today(UUID tenant) {
        String zone = jdbc.queryForObject("select timezone from tenants where id = ?", String.class, tenant);
        return LocalDate.now(ZoneId.of(zone));
    }

    @GetMapping("/subjects")
    public List<String> subjects() {
        UUID tenant = TenantContext.require();
        return jdbc.queryForList("""
            select name from subjects where tenant_id = ?
            union select subject as name from syllabus_class_subjects where tenant_id = ?
            union select subject as name from teacher_workloads where tenant_id = ?
            order by name
            """, String.class, tenant, tenant, tenant);
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        UUID tenant = TenantContext.require();
        LocalDate day = today(tenant);
        var data = new LinkedHashMap<String, Object>();
        data.put("date", day);
        data.put("students", jdbc.queryForObject("select count(*) from students where tenant_id = ? and active", Long.class, tenant));
        data.put("teachers", jdbc.queryForObject("select count(*) from teachers where tenant_id = ? and active", Long.class, tenant));
        data.put("attendance", jdbc.queryForMap("""
            select count(*) as marked, count(*) filter (where a.status = 'PRESENT') as present
            from attendance a join students s on s.id = a.student_id and s.tenant_id = a.tenant_id
            where a.tenant_id = ? and a.attendance_date = ? and s.active
            """, tenant, day));
        data.put("fees", jdbc.queryForMap("""
            select coalesce(sum(i.amount), 0) as total,
              coalesce(sum(coalesce(p.paid, 0)), 0) as collected,
              coalesce(sum(greatest(i.amount - coalesce(p.paid, 0), 0)), 0) as pending
            from invoices i left join (
              select tenant_id, invoice_id, sum(amount) as paid from payments
              where status = 'SUCCESS' group by tenant_id, invoice_id
            ) p on p.invoice_id = i.id and p.tenant_id = i.tenant_id where i.tenant_id = ?
            """, tenant));
        data.put("pendingApprovals", jdbc.queryForObject("""
            select (select count(*) from teacher_leave where tenant_id = ? and status = 'PENDING')
              + (select count(*) from teacher_attendance where tenant_id = ? and status = 'PENDING')
              + (select count(*) from teacher_adjustments where tenant_id = ? and status = 'PENDING')
              + (select count(*) from student_discipline where tenant_id = ? and status = 'PENDING')
            """, Long.class, tenant, tenant, tenant, tenant));
        data.put("attendanceTrend", jdbc.queryForList("""
            select date_trunc('month', attendance_date)::date as month, count(*) as marked,
              count(*) filter (where status = 'PRESENT') as present
            from attendance where tenant_id = ? and attendance_date >= ? and attendance_date <= ?
            group by 1 order by 1
            """, tenant, day.withDayOfMonth(1).minusMonths(11), day));
        data.put("recentAttendance", jdbc.queryForList("""
            select a.id, a.student_id, s.display_name, s.class_name, s.section_name, a.status, a.attendance_date
            from attendance a join students s on s.id = a.student_id and s.tenant_id = a.tenant_id
            where a.tenant_id = ? and a.attendance_date = ? and s.active
            order by s.display_name limit 8
            """, tenant, day));
        data.put("activity", jdbc.queryForList("""
            select * from (
              select 'Announcement published' as title, title as detail, published_at as occurred_at
                from announcements where tenant_id = ? and published_at <= now()
              union all
              select 'Payment received', coalesce(provider_reference, 'Payment') || ' - ' || amount::text, paid_at
                from payments where tenant_id = ? and status = 'SUCCESS' and paid_at is not null
              union all
              select 'Student registered', display_name, created_at from students where tenant_id = ?
              union all
              select 'Teacher registered', display_name, created_at from teachers where tenant_id = ?
            ) events where occurred_at is not null order by occurred_at desc limit 8
            """, tenant, tenant, tenant, tenant));
        return data;
    }

    @GetMapping("/records/{type}")
    public List<Map<String, Object>> records(@PathVariable String type,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        UUID tenant = TenantContext.require();
        if (type.equals("attendance") || type.equals("attendance-analytics")) {
            LocalDate end = to == null ? today(tenant) : to;
            LocalDate start = from == null ? end.withDayOfMonth(1) : from;
            if (start.isAfter(end)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Start date must be before end date");
            if (type.equals("attendance-analytics")) return jdbc.queryForList("""
                select a.attendance_date, count(*) as marked,
                  count(*) filter (where a.status = 'PRESENT') as present,
                  count(*) filter (where a.status = 'ABSENT') as absent,
                  count(*) filter (where a.status = 'LATE') as late,
                  round(100.0 * count(*) filter (where a.status = 'PRESENT') / nullif(count(*), 0), 1) as present_percent
                from attendance a where a.tenant_id = ? and a.attendance_date between ? and ?
                group by a.attendance_date order by a.attendance_date desc
                """, tenant, start, end);
            return jdbc.queryForList("""
                select a.id, s.display_name as student, s.user_id, s.class_name, s.section_name, a.attendance_date, a.status
                from attendance a join students s on s.id = a.student_id and s.tenant_id = a.tenant_id
                where a.tenant_id = ? and a.attendance_date between ? and ? order by a.attendance_date desc, s.display_name
                """, tenant, start, end);
        }
        String sql = switch (type) {
            case "exams" -> """
                select e.id, e.name as exam, y.name as academic_year,
                  (select count(*) from results r where r.exam_id = e.id and r.tenant_id = e.tenant_id) as result_count
                from exams e left join academic_years y on y.id = e.academic_year_id and y.tenant_id = e.tenant_id
                where e.tenant_id = ? order by e.name
                """;
            case "results" -> """
                select r.id, r.student_id, r.exam_id, r.subject_id, s.display_name as student, s.class_name, s.section_name, e.name as exam,
                  sub.name as subject, r.marks, r.grade
                from results r join students s on s.id = r.student_id and s.tenant_id = r.tenant_id
                join exams e on e.id = r.exam_id and e.tenant_id = r.tenant_id
                left join subjects sub on sub.id = r.subject_id and sub.tenant_id = r.tenant_id
                where r.tenant_id = ? order by e.name, s.display_name, sub.name
                """;
            case "performance" -> """
                select s.id, s.display_name as student, s.user_id, s.class_name, s.section_name,
                  (select count(*) from results r where r.tenant_id = s.tenant_id and r.student_id = s.id) as result_count,
                  (select round(avg(r.marks), 2) from results r where r.tenant_id = s.tenant_id and r.student_id = s.id) as average_marks,
                  (select round(100.0 * count(*) filter (where a.status = 'PRESENT') / nullif(count(*), 0), 1)
                    from attendance a where a.tenant_id = s.tenant_id and a.student_id = s.id) as attendance_percent
                from students s where s.tenant_id = ? and s.active order by s.display_name
                """;
            case "reports" -> """
                select c.id, c.name as class_name, sec.name as section_name,
                  (select count(*) from students s where s.tenant_id = c.tenant_id and s.active
                    and (s.section_id = sec.id or (s.class_name = c.name and s.section_name = sec.name))) as students,
                  (select count(*) from teacher_workloads w where w.tenant_id = c.tenant_id and w.status = 'ACTIVE'
                    and w.class_name = c.name and w.section_name = sec.name) as teaching_assignments
                from classes c left join sections sec on sec.class_id = c.id and sec.tenant_id = c.tenant_id and sec.active
                where c.tenant_id = ? and c.active order by c.name, sec.name
                """;
            case "documents" -> """
                select id, file_name, class_name, academic_year, period, updated_at, 'SYLLABUS' as source
                from class_syllabus_documents where tenant_id = ?
                union all
                select id, file_name, null, null, null, uploaded_at, 'SCHOOL' as source
                from admin_documents where tenant_id = ? and student_id is null order by updated_at desc
                """;
            case "student-documents" -> """
                select d.id, d.file_name, s.display_name as student, s.user_id, s.class_name, s.section_name, d.uploaded_at
                from admin_documents d join students s on s.id = d.student_id and s.tenant_id = d.tenant_id
                where d.tenant_id = ? order by d.uploaded_at desc
                """;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown admin dataset");
        };
        return type.equals("documents") ? jdbc.queryForList(sql, tenant, tenant) : jdbc.queryForList(sql, tenant);
    }

    @GetMapping("/students/{id}/insights")
    public Map<String, Object> studentInsights(@PathVariable UUID id) {
        UUID tenant = TenantContext.require();
        if (jdbc.queryForObject("select count(*) from students where id = ? and tenant_id = ?", Integer.class, id, tenant) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found");
        return Map.of("attendance", jdbc.queryForMap("""
            select count(*) as marked, count(*) filter (where status = 'PRESENT') as present
            from attendance where tenant_id = ? and student_id = ?
            """, tenant, id), "trend", jdbc.queryForList("""
            select date_trunc('month', attendance_date)::date as month,
              round(100.0 * count(*) filter (where status = 'PRESENT') / nullif(count(*), 0), 1) as percent
            from attendance where tenant_id = ? and student_id = ? group by 1 order by 1
            """, tenant, id), "results", jdbc.queryForList("""
            select r.id, e.name as exam, sub.name as subject, r.marks, r.grade
            from results r join exams e on e.id = r.exam_id and e.tenant_id = r.tenant_id
            left join subjects sub on sub.id = r.subject_id and sub.tenant_id = r.tenant_id
            where r.tenant_id = ? and r.student_id = ? order by e.name, sub.name
            """, tenant, id));
    }
}
