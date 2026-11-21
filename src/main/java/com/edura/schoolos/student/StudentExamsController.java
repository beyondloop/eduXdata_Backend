package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/student/exams")
@PreAuthorize("hasAnyRole('STUDENT','TEACHER')")
public class StudentExamsController {
    private final JdbcTemplate jdbc;

    public StudentExamsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<Map<String, Object>> upcoming(Authentication auth) {
        boolean teacher = auth.getAuthorities().stream().anyMatch(authority -> "ROLE_TEACHER".equals(authority.getAuthority()));
        if (teacher) return jdbc.queryForList("""
            select s.id, s.subject as name, s.exam_date, s.exam_time, s.exam_term from exam_schedule_items s
            where s.tenant_id = ? and s.exam_date >= current_date
            order by s.exam_date, s.exam_time, s.subject
            """, TenantContext.require());
        return jdbc.queryForList("""
            select schedule.id, schedule.subject as name, schedule.exam_date, schedule.exam_time, schedule.exam_term
            from exam_schedule_items schedule
            join students student on student.tenant_id = schedule.tenant_id and student.user_id = ? and student.active
            where schedule.tenant_id = ? and schedule.exam_date >= current_date
            order by schedule.exam_date, schedule.exam_time, schedule.subject
            """, auth.getName(), TenantContext.require());
    }
}
