package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admission-enquiries")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class AdmissionEnquiryController {
    private final JdbcTemplate jdbc;

    public AdmissionEnquiryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select id, student_name, parent_name, phone, email, desired_class, message, created_at from admission_enquiries where tenant_id = ? order by created_at desc", TenantContext.require());
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody EnquiryRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admission_enquiries(id, tenant_id, student_name, parent_name, phone, email, desired_class, message) values (?, ?, ?, ?, ?, ?, ?, ?)",
            id, TenantContext.require(), request.studentName().trim(), request.parentName().trim(), request.phone().trim(), blankToNull(request.email()), request.desiredClass().trim(), blankToNull(request.message()));
        return Map.of("id", id, "studentName", request.studentName().trim(), "status", "RECEIVED");
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record EnquiryRequest(@NotBlank String studentName, @NotBlank String parentName, @NotBlank String phone,
                                 String email, @NotBlank String desiredClass, String message) {}
}
