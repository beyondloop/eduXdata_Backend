package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class ClassSectionController {
    private final JdbcTemplate jdbc;
    public ClassSectionController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/classes")
    public List<Map<String, Object>> classes() {
        return jdbc.queryForList("""
            select c.id, c.name,
              (select count(*) from sections sec where sec.tenant_id = c.tenant_id and sec.class_id = c.id and sec.active) as section_count,
              (select count(*) from students s
                left join sections sec on sec.id = s.section_id and sec.tenant_id = s.tenant_id
                where s.tenant_id = c.tenant_id and s.active
                and (sec.class_id = c.id or (sec.id is null and lower(trim(s.class_name)) = lower(trim(c.name))))) as student_count
            from classes c where c.tenant_id = ? and c.active order by c.name
            """, TenantContext.require());
    }

    @GetMapping("/sections")
    public List<Map<String, Object>> sections() {
        return jdbc.queryForList("""
            select sec.id, sec.name, c.id as class_id, c.name as class_name,
              (select count(*) from students s where s.tenant_id = sec.tenant_id and s.active
                and (s.section_id = sec.id or (s.section_id is null
                  and lower(trim(s.class_name)) = lower(trim(c.name))
                  and lower(trim(s.section_name)) = lower(trim(sec.name))))) as student_count
            from sections sec join classes c on c.id = sec.class_id and c.tenant_id = sec.tenant_id
            where sec.tenant_id = ? and sec.active and c.active order by c.name, sec.name
            """, TenantContext.require());
    }

    @PostMapping("/classes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createClass(@Valid @RequestBody ClassRequest request) {
        UUID tenant = TenantContext.require();
        String name = request.name().trim();
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from classes where tenant_id = ? and lower(trim(name)) = lower(?))", Boolean.class, tenant, name)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This class already exists");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into classes(id, tenant_id, name) values (?, ?, ?)", id, tenant, name);
        return Map.of("id", id, "name", name);
    }

    @DeleteMapping("/classes/{classId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteClass(@PathVariable UUID classId) {
        UUID tenant = TenantContext.require();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from classes where id = ? and tenant_id = ? and active)", Boolean.class, classId, tenant)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Class not found");
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sections where tenant_id = ? and class_id = ? and active)", Boolean.class, tenant, classId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delete this class's sections before deleting the class");
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from students where tenant_id = ? and active and (section_id in (select id from sections where tenant_id = ? and class_id = ?) or lower(trim(class_name)) = lower(trim((select name from classes where id = ? and tenant_id = ?)))))", Boolean.class, tenant, tenant, classId, classId, tenant)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A class with registered students cannot be deleted");
        jdbc.update("update classes set active = false where id = ? and tenant_id = ?", classId, tenant);
    }

    @PostMapping("/sections")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSection(@Valid @RequestBody SectionRequest request) {
        UUID tenant = TenantContext.require();
        String name = request.name().trim();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from classes where id = ? and tenant_id = ? and active)", Boolean.class, request.classId(), tenant)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an active class");
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sections where tenant_id = ? and class_id = ? and lower(trim(name)) = lower(?))", Boolean.class, tenant, request.classId(), name)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This section already exists in the selected class");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into sections(id, tenant_id, class_id, name) values (?, ?, ?, ?)", id, tenant, request.classId(), name);
        return Map.of("id", id, "name", name, "class_id", request.classId());
    }

    public record ClassRequest(@NotBlank @Size(max = 80) String name) {}
    public record SectionRequest(@NotNull UUID classId, @NotBlank @Size(max = 30) String name) {}
}
