package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/teachers")
public class TeacherController {
    private final JdbcTemplate jdbc;
    public TeacherController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "") String q) {
        var tenant = TenantContext.require();
        String search = "%" + q + "%";
        return jdbc.queryForList("select id, display_name, email, teacher_id, user_id, registration_type, active from teachers where tenant_id = ? and (lower(display_name) like lower(?) or lower(email) like lower(?) or lower(teacher_id) like lower(?) or lower(user_id) like lower(?)) order by display_name", tenant, search, search, search, search);
    }
}
