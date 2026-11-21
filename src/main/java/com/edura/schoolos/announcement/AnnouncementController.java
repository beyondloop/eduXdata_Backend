package com.edura.schoolos.announcement;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/announcements")
public class AnnouncementController {
    private final JdbcTemplate jdbc;
    public AnnouncementController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER','STUDENT','PARENT')")
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select id, title, body, audience, published_at, created_by from announcements where tenant_id = ? order by published_at desc nulls last", TenantContext.require());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public Map<String, Object> create(@Valid @RequestBody NewAnnouncement request) {
        var id = UUID.randomUUID();
        jdbc.update("insert into announcements(id, tenant_id, title, body, audience, published_at) values (?, ?, ?, ?, ?, now())", id, TenantContext.require(), request.title(), request.body(), request.audience());
        return Map.of("id", id, "title", request.title(), "audience", request.audience());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void update(@PathVariable UUID id, @Valid @RequestBody NewAnnouncement request) {
        jdbc.update("update announcements set title = ?, body = ?, audience = ? where id = ? and tenant_id = ?", request.title().trim(), request.body().trim(), request.audience().trim(), id, TenantContext.require());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) { jdbc.update("delete from announcements where id = ? and tenant_id = ?", id, TenantContext.require()); }

    public record NewAnnouncement(@NotBlank String title, @NotBlank String body, @NotBlank String audience) {}
}
