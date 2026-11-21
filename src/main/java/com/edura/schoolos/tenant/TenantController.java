package com.edura.schoolos.tenant;

import com.edura.schoolos.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenant")
public class TenantController {
    private final JdbcTemplate jdbc;
    public TenantController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/context")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN','TEACHER','PARENT','STUDENT')")
    public Map<String, Object> context() {
        var tenant = TenantContext.require();
        var context = new java.util.LinkedHashMap<String, Object>(jdbc.queryForMap("select name, timezone from tenants where id = ?", tenant));
        context.put("tenantId", tenant);
        context.put("academicYears", jdbc.queryForList("""
            select name, bool_or(active) as active from (
              select name, active from academic_years where tenant_id = ?
              union all select distinct academic_year as name, false as active from invoices where tenant_id = ?
            ) years group by name order by name desc
            """, tenant, tenant));
        return context;
    }
}
