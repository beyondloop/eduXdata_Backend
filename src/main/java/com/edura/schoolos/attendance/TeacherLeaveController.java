package com.edura.schoolos.attendance;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/teacher-leave")
public class TeacherLeaveController {
    private final JdbcTemplate jdbc;
    public TeacherLeaveController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public List<Map<String, Object>> list(Authentication auth) {
        String sql = "select l.id, l.tenant_id, l.teacher_id, l.leave_type, l.from_date, l.to_date, l.reason, l.status, l.reviewed_by, l.submitted_at, l.reviewed_at, l.proof_name, t.display_name as teacher_name from teacher_leave l join teachers t on t.id = l.teacher_id and t.tenant_id = l.tenant_id where l.tenant_id = ?";
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            return LeavePolicy.withPayBreakdown(jdbc.queryForList(sql + " order by l.submitted_at desc", TenantContext.require()));
        return LeavePolicy.withPayBreakdown(jdbc.queryForList(sql + " and t.user_id = ? order by l.submitted_at desc", TenantContext.require(), auth.getName()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public Map<String, Object> apply(@Valid @RequestBody Application entry, Authentication auth) {
        return save(entry, null, auth);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public Map<String, Object> applyWithProof(@Valid @RequestPart("application") Application entry,
            @RequestPart(value = "proof", required = false) MultipartFile proof, Authentication auth) {
        return save(entry, proof, auth);
    }

    private Map<String, Object> save(Application entry, MultipartFile proof, Authentication auth) {
        if (entry.toDate().isBefore(entry.fromDate())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End date must be on or after start date");
        UUID tenant = TenantContext.require();
        // Serialize applications for a teacher so concurrent requests cannot overlap.
        var teachers = jdbc.queryForList("select id from teachers where tenant_id = ? and user_id = ? and active for update", tenant, auth.getName());
        if (teachers.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active teacher account required");
        Object teacher = teachers.get(0).get("id");
        Integer overlaps = jdbc.queryForObject("select count(*) from teacher_leave where tenant_id = ? and teacher_id = ? and status in ('PENDING','APPROVED') and from_date <= ? and to_date >= ?", Integer.class, tenant, teacher, entry.toDate(), entry.fromDate());
        if (overlaps != null && overlaps > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have pending or approved leave for these dates");
        var history = jdbc.queryForList("select leave_type, from_date, to_date from teacher_leave where tenant_id = ? and teacher_id = ? and status in ('PENDING','APPROVED')", tenant, teacher).stream()
            .map(row -> new LeavePolicy.Period(row.get("leave_type").toString(), LocalDate.parse(row.get("from_date").toString()), LocalDate.parse(row.get("to_date").toString()))).toList();
        if ("EMERGENCY".equals(entry.leaveType()) && !LeavePolicy.emergencyAvailable(entry.fromDate(), entry.toDate(), history))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Emergency leave is available only after both sick and casual leave balances are exhausted for each requested calendar year");
        if (LeavePolicy.proofRequired(entry.leaveType(), entry.fromDate(), entry.toDate(), history) && (proof == null || proof.isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supporting proof is required for emergency leave, more than two consecutive days or leave beyond the five-day annual allowance");
        byte[] data = null;
        String name = null, type = null;
        if (proof != null && !proof.isEmpty()) {
            if (proof.getSize() > 5 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proof must be 5 MB or smaller");
            try { data = proof.getBytes(); } catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read proof attachment"); }
            type = proofType(data);
            name = Optional.ofNullable(proof.getOriginalFilename()).orElse("supporting-document").replaceAll("[^a-zA-Z0-9._ -]", "_");
            if (name.isBlank()) name = "supporting-document";
            name = name.substring(0, Math.min(name.length(), 180));
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into teacher_leave(id, tenant_id, teacher_id, leave_type, from_date, to_date, reason, proof_name, proof_type, proof_data) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, tenant, teacher, entry.leaveType(), entry.fromDate(), entry.toDate(), entry.reason().trim(), name, type, data);
        return Map.of("id", id, "status", "PENDING");
    }

    private static String proofType(byte[] data) {
        if (data.length >= 5 && new String(data, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if (data.length >= 3 && (data[0] & 255) == 255 && (data[1] & 255) == 216 && (data[2] & 255) == 255) return "image/jpeg";
        if (data.length >= 8 && Arrays.equals(Arrays.copyOf(data, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attach a PDF, JPG or PNG document");
    }

    @GetMapping("/{id}/proof")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<byte[]> proof(@PathVariable UUID id, Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        String sql = "select l.proof_name, l.proof_type, l.proof_data from teacher_leave l join teachers t on t.id = l.teacher_id and t.tenant_id = l.tenant_id where l.id = ? and l.tenant_id = ? and l.proof_data is not null";
        var rows = admin ? jdbc.queryForList(sql + " and exists (select 1 from admins ad where ad.tenant_id = l.tenant_id and ad.user_id = ? and ad.active)", id, TenantContext.require(), auth.getName())
            : jdbc.queryForList(sql + " and t.user_id = ?", id, TenantContext.require(), auth.getName());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found");
        var row = rows.get(0);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(row.get("proof_type").toString()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(row.get("proof_name").toString(), StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body((byte[]) row.get("proof_data"));
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> decide(@PathVariable UUID id, @Valid @RequestBody Decision decision, Authentication auth) {
        UUID tenant = TenantContext.require();
        var admins = jdbc.queryForList("select id from admins where tenant_id = ? and user_id = ? and active", tenant, auth.getName());
        if (admins.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active admin account required");
        String status = decision.approve() ? "APPROVED" : "REJECTED";
        int changed = jdbc.update("update teacher_leave set status = ?, reviewed_by = ?, reviewed_at = now() where id = ? and tenant_id = ? and status = 'PENDING'", status, admins.get(0).get("id"), id, tenant);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Request not found or already reviewed");
        return Map.of("status", status);
    }

    public record Application(@NotNull @Pattern(regexp = "SICK|CASUAL|EMERGENCY") String leaveType, @NotNull LocalDate fromDate, @NotNull LocalDate toDate, @NotBlank @Size(max = 1000) String reason) {}
    public record Decision(@NotNull Boolean approve) {}
}
