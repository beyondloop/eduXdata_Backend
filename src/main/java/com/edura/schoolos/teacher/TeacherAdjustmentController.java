package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Time;
import java.util.*;

@RestController
@RequestMapping("/api/v1/teacher-adjustments")
public class TeacherAdjustmentController {
    private final JdbcTemplate jdbc;
    private final TeacherWorkloadController workloads;
    public TeacherAdjustmentController(JdbcTemplate jdbc, TeacherWorkloadController workloads) { this.jdbc = jdbc; this.workloads = workloads; }

    private static final String ELIGIBLE = """
        t.tenant_id=? and t.active and t.user_id<>?
        and not exists(select 1 from teacher_workloads w where w.tenant_id=t.tenant_id and w.teacher_user_id=t.user_id
          and lower(trim(w.day_of_week))=lower(trim(?)) and w.start_time < ? and w.end_time > ?)
        and exists(select 1 from teacher_workloads w where w.tenant_id=t.tenant_id and w.teacher_user_id=t.user_id
          and w.status='ACTIVE' and lower(trim(w.subject))=lower(trim(?)))
        and (lower(trim(?))<>'class teacher' or (
          not exists(select 1 from teacher_workloads w where w.tenant_id=t.tenant_id and w.teacher_user_id=t.user_id
            and lower(trim(w.teacher_role))='class teacher' and w.id<>?
            and (w.class_name<>? or w.section_name<>?))
          and not exists(select 1 from teacher_workloads w where w.tenant_id=t.tenant_id
            and lower(trim(w.teacher_role))='class teacher' and w.id<>? and w.class_name=? and w.section_name=?
            and w.teacher_user_id<>t.user_id)))
        """;
    private Object[] eligibilityArgs(UUID tenant,String requester,Map<String,Object> slot) {
        return new Object[]{tenant,requester,slot.get("day_of_week"),slot.get("end_time"),slot.get("start_time"),slot.get("subject"),
          slot.get("teacher_role"),slot.get("id"),slot.get("class_name"),slot.get("section_name"),slot.get("id"),slot.get("class_name"),slot.get("section_name")};
    }
    private void requireEligible(UUID tenant,String requester,String replacement,Map<String,Object> slot) {
        var args=new ArrayList<Object>(Arrays.asList(eligibilityArgs(tenant,requester,slot)));args.add(replacement);
        Integer eligible=jdbc.queryForObject("select count(*) from teachers t where " + ELIGIBLE + " and t.user_id=?",Integer.class,args.toArray());
        if(eligible==null || eligible!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"This teacher cannot cover this subject and period: check overlapping classes and class-teacher assignments. Choose an available subject teacher.");
    }
    @GetMapping("/available-teachers")
    @PreAuthorize("hasRole('TEACHER')")
    public List<Map<String,Object>> availableTeachers(@RequestParam UUID workloadId, Authentication auth) {
        var tenant=TenantContext.require();
        var slots=jdbc.queryForList("select * from teacher_workloads where tenant_id=? and id=? and teacher_user_id=? and status='ACTIVE'",tenant,workloadId,auth.getName());
        if(slots.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Select one of your active workloads");
        var slot=slots.get(0);
        return jdbc.queryForList("select t.user_id,t.display_name from teachers t where " + ELIGIBLE + " order by t.display_name", eligibilityArgs(tenant,auth.getName(),slot));
    }
    @GetMapping("/teachers")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public List<Map<String,Object>> teachers() {
        return jdbc.queryForList("select user_id, display_name from teachers where tenant_id = ? and active order by display_name", TenantContext.require());
    }
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public List<Map<String,Object>> list(Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        String filter = admin ? "" : " and (a.requested_by = ? or a.replacement_user_id = ?)";
        Object[] args = admin ? new Object[]{TenantContext.require()} : new Object[]{TenantContext.require(), auth.getName(), auth.getName()};
        return jdbc.queryForList("""
            select a.*, coalesce(a.subject,w.subject) as subject, coalesce(a.class_name,w.class_name) as class_name, coalesce(a.section_name,w.section_name) as section_name, coalesce(a.day_of_week,w.day_of_week) as day_of_week, coalesce(a.start_time,w.start_time) as start_time,
            t.display_name as replacement_name, r.display_name as requester_name
            from teacher_adjustments a left join teacher_workloads w on w.id = a.workload_id and w.tenant_id=a.tenant_id
            join teachers t on t.tenant_id = a.tenant_id and t.user_id = a.replacement_user_id
            join teachers r on r.tenant_id = a.tenant_id and r.user_id = a.requested_by
            where a.tenant_id = ?
            """ + filter + " order by a.created_at desc", args);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public Map<String,Object> request(@Valid @RequestBody Request request, Authentication auth) {
        var tenant = TenantContext.require();
        jdbc.queryForList("select id from tenants where id = ? for update",tenant);
        var rows = jdbc.queryForList("select * from teacher_workloads where tenant_id = ? and id = ? and teacher_user_id = ? and status = 'ACTIVE' for update", tenant, request.workloadId(), auth.getName());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Select one of your assigned workloads");
        if (auth.getName().equals(request.replacementUserId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select another teacher");
        if (jdbc.queryForObject("select count(*) from teachers where tenant_id = ? and user_id = ? and active", Integer.class, tenant, request.replacementUserId()) != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an active replacement teacher");
        if (jdbc.queryForObject("select count(*) from teacher_adjustments where workload_id = ? and status = 'PENDING'", Integer.class, request.workloadId()) != 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This workload already has a pending request");
        var slot=rows.get(0);
        Integer conflicts=jdbc.queryForObject("select count(*) from teacher_workloads where tenant_id=? and teacher_user_id=? and status='ACTIVE' and lower(day_of_week)=lower(?) and start_time < ? and end_time > ?",Integer.class,tenant,request.replacementUserId(),slot.get("day_of_week"),slot.get("end_time"),slot.get("start_time"));
        if(conflicts!=null && conflicts>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"This teacher is no longer available for the selected period. Choose another teacher.");
        requireEligible(tenant,auth.getName(),request.replacementUserId(),slot);
        var id = UUID.randomUUID();
        jdbc.update("insert into teacher_adjustments(id, tenant_id, workload_id, requested_by, replacement_user_id, reason, subject, class_name, section_name, day_of_week, start_time, end_time) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, tenant, request.workloadId(), auth.getName(), request.replacementUserId(), request.reason().trim(),slot.get("subject"),slot.get("class_name"),slot.get("section_name"),slot.get("day_of_week"),slot.get("start_time"),slot.get("end_time"));
        return Map.of("id", id, "status", "PENDING");
    }
    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String,Object> decide(@PathVariable UUID id, @Valid @RequestBody Decision decision, Authentication auth) {
        var tenant = TenantContext.require();
        // Serialize approvals in a school so competing replacements cannot acquire the same slot.
        jdbc.queryForList("select id from tenants where id = ? for update", tenant);
        var rows = jdbc.queryForList("select * from teacher_adjustments where tenant_id = ? and id = ? and status = 'PENDING' for update", tenant, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Request unavailable or already reviewed");
        var adjustment = rows.get(0);
        if (decision.approve()) {
            var slots = jdbc.queryForList("select * from teacher_workloads where tenant_id = ? and id = ? for update", tenant, adjustment.get("workload_id"));
            if (slots.isEmpty() || !adjustment.get("requested_by").equals(slots.get(0).get("teacher_user_id")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Workload assignment has changed; reject this request and submit a new one");
            var slot = slots.get(0);
            if(!"ACTIVE".equals(slot.getOrDefault("status","ACTIVE"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"This class assignment is no longer active. Reject this request.");
            for(String field:List.of("subject","class_name","section_name","day_of_week","start_time","end_time")) {
                if(adjustment.get(field)!=null && !Objects.equals(adjustment.get(field),slot.get(field)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"The class schedule changed after this request. Reject it and request the current class period.");
            }
            String replacement = (String)adjustment.get("replacement_user_id");
            var names = jdbc.queryForList("select display_name from teachers where tenant_id = ? and user_id = ? and active for update", tenant, replacement);
            if (names.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Replacement teacher is no longer active");
            requireEligible(tenant,(String)adjustment.get("requested_by"),replacement,slot);
            workloads.update((UUID)slot.get("id"), new TeacherWorkloadController.CreateWorkload(
                (String)names.get(0).get("display_name"), replacement, (String)slot.get("teacher_role"), (String)slot.get("subject"),
                (String)slot.get("class_name"), (String)slot.get("section_name"), (String)slot.get("day_of_week"),
                ((Time)slot.get("start_time")).toLocalTime(), ((Time)slot.get("end_time")).toLocalTime(), ((Number)slot.get("duration_minutes")).intValue()));
        }
        String status = decision.approve() ? "APPROVED" : "REJECTED";
        jdbc.update("update teacher_adjustments set status = ?, reviewed_by = ?, reviewed_at = now() where tenant_id = ? and id = ?", status, auth.getName(), tenant, id);
        return Map.of("status", status);
    }
    public record Request(@NotNull UUID workloadId, @NotBlank String replacementUserId, @NotBlank @Size(max=2000) String reason) {}
    public record Decision(@NotNull Boolean approve) {}
}
