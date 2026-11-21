package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/api/v1/teacher-performance")
@PreAuthorize("hasRole('ADMIN')")
public class TeacherPerformanceController {
    private final JdbcTemplate jdbc;
    public TeacherPerformanceController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/rules")
    public Map<String,Object> rules() {
        var rows = jdbc.queryForList("select on_time, late from syllabus_point_rules where tenant_id=?", TenantContext.require());
        return rows.isEmpty() ? Map.of("on_time", 5, "late", 0) : rows.get(0);
    }
    @PutMapping("/rules")
    public Map<String,Object> rules(@Valid @RequestBody Rules rules) {
        jdbc.update("insert into syllabus_point_rules(tenant_id,on_time,late) values(?,?,?) on conflict(tenant_id) do update set on_time=excluded.on_time,late=excluded.late,updated_at=now()",
            TenantContext.require(), rules.onTime(), rules.late());
        return rules();
    }
    // Completion attribution is immutable. Pending work follows the explicit or current workload assignment.
    @GetMapping
    @Transactional(readOnly=true)
    public Map<String,Object> list() {
        var topics = jdbc.queryForList("""
            select t.id,t.title,t.deadline,t.deadline_time,t.completed_at,t.completion_status,t.points,
              s.class_name,s.section_name,s.subject,s.period,
              coalesce(s.academic_year, to_char(s.year_start,'YYYY') || '-' || to_char(s.year_start + interval '1 year','YY'), 'General') as academic_year,
              to_char(t.deadline,'YYYY-MM') as month,
              coalesce(t.completed_by,s.assigned_teacher,w.teacher_user_id) as teacher_user_id,
              coalesce(teacher.display_name,t.completed_by,s.assigned_teacher,w.teacher_user_id,'Unassigned') as teacher_name,
              teacher.teacher_id as employee_id, teacher.email
            from syllabus_topics t join syllabuses s on s.id=t.syllabus_id
            left join lateral (select distinct tw.teacher_user_id from teacher_workloads tw
              join teachers active_teacher on active_teacher.tenant_id=tw.tenant_id and active_teacher.user_id=tw.teacher_user_id and active_teacher.active
              where tw.tenant_id=s.tenant_id and tw.class_name=s.class_name and tw.section_name=s.section_name
                and lower(tw.subject)=lower(s.subject) and tw.status='ACTIVE' order by tw.teacher_user_id limit 1) w on true
            left join teachers teacher on teacher.tenant_id=s.tenant_id and teacher.user_id=coalesce(t.completed_by,s.assigned_teacher,w.teacher_user_id)
            where s.tenant_id=? order by t.deadline,t.id
            """, TenantContext.require());
        return Map.of("topics", topics, "remarks", jdbc.queryForList("select teacher_user_id,academic_year,remarks,updated_at from teacher_performance_remarks where tenant_id=?", TenantContext.require()));
    }
    @PutMapping("/remarks/{teacher}")
    public Map<String,Object> remarks(@PathVariable String teacher, @Valid @RequestBody Remark entry, Authentication auth) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from teachers where tenant_id=? and user_id=?)", Boolean.class, TenantContext.require(), teacher)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Teacher not found");
        jdbc.update("insert into teacher_performance_remarks(tenant_id,teacher_user_id,academic_year,remarks,updated_by) values(?,?,?,?,?) on conflict(tenant_id,teacher_user_id,academic_year) do update set remarks=excluded.remarks,updated_by=excluded.updated_by,updated_at=now()",
            TenantContext.require(),teacher,entry.academicYear(),entry.remarks().trim(),auth.getName());
        return Map.of("saved",true);
    }
    public record Rules(@NotNull @Min(0) @Max(1000) Integer onTime, @NotNull @Min(0) @Max(1000) Integer late) {}
    public record Remark(@NotBlank @Size(max=20) String academicYear, @NotNull @Size(max=4000) String remarks) {}
}
