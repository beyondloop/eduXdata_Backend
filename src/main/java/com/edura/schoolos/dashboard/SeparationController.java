package com.edura.schoolos.dashboard;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/separations")
@PreAuthorize("hasRole('ADMIN')")
public class SeparationController {
    private final JdbcTemplate jdbc;
    public SeparationController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<Map<String, Object>> list() {
        var rows = jdbc.queryForList("""
            select r.id, r.student_id, r.teacher_id, r.requested_last_day, r.reason, r.status, r.created_at, r.completed_at,
              coalesce(s.display_name, t.display_name) as person_name, coalesce(s.user_id, t.user_id) as user_id,
              case when r.student_id is null then 'TEACHER' else 'STUDENT' end as person_type
            from separation_requests r
            left join students s on s.id = r.student_id and s.tenant_id = r.tenant_id
            left join teachers t on t.id = r.teacher_id and t.tenant_id = r.tenant_id
            where r.tenant_id = ? order by r.created_at desc
            """, TenantContext.require());
        var items = jdbc.queryForList("""
            select i.id, i.request_id, i.label, i.completed, i.updated_at from separation_checklist_items i
            join separation_requests r on r.id = i.request_id where r.tenant_id = ? order by i.sort_order
            """, TenantContext.require());
        rows.forEach(row -> row.put("checklist", items.stream().filter(item -> row.get("id").equals(item.get("request_id"))).toList()));
        return rows;
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return jdbc.queryForList("select id, person_type, label, sort_order from separation_checklist_templates where tenant_id = ? order by person_type, sort_order", TenantContext.require());
    }

    @PostMapping("/templates")
    @Transactional
    public Map<String, Object> addTemplate(@Valid @RequestBody Template request) {
        var tenant = TenantContext.require();
        jdbc.queryForObject("select id from tenants where id = ? for update", UUID.class, tenant);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into separation_checklist_templates(id, tenant_id, person_type, label, sort_order)
            select ?, ?, ?, ?, coalesce(max(sort_order), 0) + 1 from separation_checklist_templates
            where tenant_id = ? and person_type = ?
            """, id, tenant, request.personType(), request.label().trim(), tenant, request.personType());
        return Map.of("id", id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody Request request, Authentication auth) {
        UUID tenant = TenantContext.require();
        boolean student = request.personType().equals("STUDENT");
        String table = student ? "students" : "teachers";
        if (jdbc.queryForObject("select count(*) from " + table + " where id = ? and tenant_id = ? and active", Integer.class, request.personId(), tenant) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active school member not found");
        if (jdbc.queryForObject("select count(*) from separation_checklist_templates where tenant_id = ? and person_type = ?", Integer.class, tenant, request.personType()) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configure clearance checks before starting a request");
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into separation_requests(id, tenant_id, student_id, teacher_id, requested_last_day, reason, created_by) values (?, ?, ?, ?, ?, ?, ?)",
                id, tenant, student ? request.personId() : null, student ? null : request.personId(), request.lastDay(), request.reason().trim(), auth.getName());
        } catch (DuplicateKeyException conflict) { throw new ResponseStatusException(HttpStatus.CONFLICT, "This person already has an open separation request"); }
        jdbc.update("""
            insert into separation_checklist_items(request_id, label, sort_order)
            select ?, label, sort_order from separation_checklist_templates where tenant_id = ? and person_type = ?
            """, id, tenant, request.personType());
        return Map.of("id", id);
    }

    private void requireOpen(UUID id) {
        var rows = jdbc.queryForList("select status from separation_requests where tenant_id = ? and id = ? for update", TenantContext.require(), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Separation request not found");
        if (!rows.get(0).get("status").equals("IN_PROGRESS")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Clearance is already completed");
    }

    @PutMapping("/{id}/items/{itemId}")
    @Transactional
    public Map<String, Object> updateItem(@PathVariable UUID id, @PathVariable UUID itemId, @Valid @RequestBody Check request, Authentication auth) {
        requireOpen(id);
        if (jdbc.update("update separation_checklist_items set completed = ?, updated_by = ?, updated_at = now() where request_id = ? and id = ?", request.completed(), auth.getName(), id, itemId) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Clearance item not found");
        return Map.of("completed", request.completed());
    }

    @PostMapping("/{id}/complete")
    @Transactional
    public Map<String, Object> complete(@PathVariable UUID id) {
        requireOpen(id);
        if (jdbc.queryForObject("select count(*) from separation_checklist_items where request_id = ? and not completed", Integer.class, id) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Complete every clearance check first");
        jdbc.update("update separation_requests set status = 'COMPLETED', completed_at = now() where tenant_id = ? and id = ?", TenantContext.require(), id);
        return Map.of("status", "COMPLETED");
    }
    public record Request(@Pattern(regexp = "STUDENT|TEACHER") @NotNull String personType, @NotNull UUID personId,
                          @NotNull LocalDate lastDay, @NotBlank @Size(max = 2000) String reason) {}
    public record Template(@Pattern(regexp = "STUDENT|TEACHER") @NotNull String personType, @NotBlank @Size(max = 500) String label) {}
    public record Check(@NotNull Boolean completed) {}
}
