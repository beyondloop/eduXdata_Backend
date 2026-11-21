package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/syllabus-subjects")
@PreAuthorize("hasRole('ADMIN')")
public class SyllabusSubjectsController {
    private final JdbcTemplate jdbc;
    public SyllabusSubjectsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select class_name, subject from syllabus_class_subjects where tenant_id = ? order by class_name, subject", TenantContext.require());
    }
    @PutMapping
    @Transactional
    public Map<String, Object> save(@Valid @RequestBody Subjects entry) {
        UUID tenant = TenantContext.require();
        jdbc.update("delete from syllabus_class_subjects where tenant_id = ? and class_name = ?", tenant, entry.className().trim());
        var unique = new TreeMap<String, String>();
        entry.subjects().forEach(subject -> unique.putIfAbsent(subject.trim().toLowerCase(Locale.ROOT), subject.trim()));
        unique.values().forEach(subject -> jdbc.update("insert into syllabus_class_subjects(tenant_id, class_name, subject) values (?, ?, ?)", tenant, entry.className().trim(), subject));
        return Map.of("className", entry.className().trim(), "subjects", unique.values());
    }
    public record Subjects(@NotBlank @Size(max = 80) String className,
        @NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 100) String> subjects) {}
}
