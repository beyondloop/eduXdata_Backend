package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
public class FacilityController {
    private static final Set<String> CATEGORIES = Set.of("BAG", "UNIFORM", "ID_CARD", "BOOK", "OTHER");
    private final JdbcTemplate jdbc;

    public FacilityController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/facility/products")
    public List<Map<String, Object>> products() {
        return jdbc.queryForList("""
            select p.id, p.name, p.category, p.size, p.available_quantity,
                   coalesce(sum(a.quantity), 0) as allocated_quantity,
                   p.created_at, p.updated_at
            from facility_products p
            left join facility_allocations a on a.tenant_id = p.tenant_id and a.product_id = p.id
            where p.tenant_id = ? and p.active = true
            group by p.id
            order by p.category, p.name, p.size nulls first
            """, TenantContext.require());
    }

    @PostMapping("/facility/products")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createProduct(@Valid @RequestBody ProductRequest request) {
        String category = category(request.category());
        UUID id = UUID.randomUUID();
        jdbc.update("insert into facility_products(id, tenant_id, name, category, size, available_quantity) values (?, ?, ?, ?, ?, ?)",
            id, TenantContext.require(), request.name().trim(), category, blankToNull(request.size()), request.quantity());
        return jdbc.queryForMap("select id, name, category, size, available_quantity, 0 as allocated_quantity from facility_products where id = ? and tenant_id = ?", id, TenantContext.require());
    }

    @PostMapping("/facility/products/{productId}/stock")
    @Transactional
    public Map<String, Object> addStock(@PathVariable UUID productId, @Valid @RequestBody StockRequest request) {
        UUID tenant = TenantContext.require();
        lockProduct(tenant, productId);
        jdbc.update("update facility_products set available_quantity = available_quantity + ?, updated_at = now() where id = ? and tenant_id = ? and active = true",
            request.quantity(), productId, tenant);
        return jdbc.queryForMap("select id, name, category, size, available_quantity from facility_products where id = ? and tenant_id = ?", productId, TenantContext.require());
    }

    @DeleteMapping("/facility/products/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<Void> deleteProduct(@PathVariable UUID productId) {
        UUID tenant = TenantContext.require();
        lockProduct(tenant, productId);
        jdbc.update("update facility_products set active = false, updated_at = now() where tenant_id = ? and id = ? and active = true", tenant, productId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/facility/products/{productId}/students")
    public List<Map<String, Object>> productStudents(@PathVariable UUID productId) {
        UUID tenant = TenantContext.require();
        requireProduct(tenant, productId);
        return jdbc.queryForList("""
            select s.id, s.user_id, s.display_name, s.email, s.roll_number,
                   coalesce(s.class_name, c.name) as class_name,
                   coalesce(s.section_name, sec.name) as section_name,
                   s.parent_contact_number,
                   coalesce(payment.paid, false) as paid,
                   coalesce((select sum(a.quantity) from facility_allocations a where a.tenant_id = s.tenant_id and a.product_id = ? and a.student_id = s.id), 0) as allocated_quantity,
                   exists(select 1 from facility_allocations a where a.tenant_id = s.tenant_id and a.product_id = ? and a.student_id = s.id) as allocated
            from students s
            left join sections sec on sec.id = s.section_id
            left join classes c on c.id = sec.class_id
            left join facility_student_payments payment on payment.tenant_id = s.tenant_id and payment.product_id = ? and payment.student_id = s.id
            where s.tenant_id = ? and s.active = true
            order by coalesce(s.class_name, c.name), coalesce(s.section_name, sec.name), s.roll_number, s.display_name
            """, productId, productId, productId, tenant);
    }

    @GetMapping("/facility/students/{studentId}/assets")
    public List<Map<String, Object>> studentAssets(@PathVariable UUID studentId) {
        UUID tenant = TenantContext.require();
        requireStudent(tenant, studentId);
        return jdbc.queryForList("""
            select p.id, p.name, p.category, p.size, p.available_quantity,
                   coalesce(payment.paid, false) as paid,
                   exists(select 1 from facility_allocations a where a.tenant_id = p.tenant_id and a.product_id = p.id and a.student_id = ?) as allocated
            from facility_products p
            left join facility_student_payments payment on payment.tenant_id = p.tenant_id and payment.product_id = p.id and payment.student_id = ?
            where p.tenant_id = ? and p.active = true
            order by p.category, p.name, p.size nulls first
            """, studentId, studentId, tenant);
    }

    @PutMapping("/facility/products/{productId}/students/{studentId}/payment")
    @Transactional
    public Map<String, Object> setPayment(@PathVariable UUID productId, @PathVariable UUID studentId, @RequestBody PaymentRequest request) {
        UUID tenant = TenantContext.require();
        lockProduct(tenant, productId);
        requireStudent(tenant, studentId);
        isPaid(tenant, productId, studentId);
        if (!request.paid() && allocationCount(tenant, productId, studentId) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A paid item cannot be marked unpaid after it has been allocated");
        jdbc.update("""
            insert into facility_student_payments(tenant_id, product_id, student_id, paid, updated_at) values (?, ?, ?, ?, now())
            on conflict (tenant_id, product_id, student_id) do update set paid = excluded.paid, updated_at = now()
            """, tenant, productId, studentId, request.paid());
        return Map.of("paid", request.paid());
    }

    @PostMapping("/facility/products/{productId}/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> allocate(@PathVariable UUID productId, @Valid @RequestBody AllocationRequest request, Authentication authentication) {
        UUID tenant = TenantContext.require();
        lockProduct(tenant, productId);
        requireStudent(tenant, request.studentId());
        if (!isPaid(tenant, productId, request.studentId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mark the facility item as paid before allocating it");
        int changed = jdbc.update("update facility_products set available_quantity = available_quantity - ?, updated_at = now() where id = ? and tenant_id = ? and available_quantity >= ?",
            request.quantity(), productId, tenant, request.quantity());
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is not enough inventory available");
        UUID allocationId = UUID.randomUUID();
        jdbc.update("insert into facility_allocations(id, tenant_id, product_id, student_id, quantity, allocated_by) values (?, ?, ?, ?, ?, ?)",
            allocationId, tenant, productId, request.studentId(), request.quantity(), authentication.getName());
        return Map.of("id", allocationId, "studentId", request.studentId(), "quantity", request.quantity());
    }

    @PostMapping("/facility/students/{studentId}/allocations")
    @Transactional
    public Map<String, Object> allocateAssets(@PathVariable UUID studentId, @Valid @RequestBody AssetAllocationRequest request, Authentication authentication) {
        UUID tenant = TenantContext.require();
        requireStudent(tenant, studentId);
        if (request.productIds() == null || request.productIds().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one asset to allocate");
        List<UUID> productIds = request.productIds().stream().distinct().sorted().toList();
        if (productIds.size() != request.productIds().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each asset can only be selected once");
        for (UUID productId : productIds) {
            lockProduct(tenant, productId);
            if (!isPaid(tenant, productId, studentId))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Every selected asset must be paid before allocation");
            if (allocationCount(tenant, productId, studentId) > 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "One of the selected assets has already been allocated");
            Integer available = jdbc.queryForObject("select available_quantity from facility_products where tenant_id = ? and id = ? for update", Integer.class, tenant, productId);
            if (available == null || available < 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "One of the selected assets is out of stock");
        }
        for (UUID productId : productIds) {
            jdbc.update("update facility_products set available_quantity = available_quantity - 1, updated_at = now() where tenant_id = ? and id = ?", tenant, productId);
            jdbc.update("insert into facility_allocations(id, tenant_id, product_id, student_id, quantity, allocated_by) values (?, ?, ?, ?, 1, ?)",
                UUID.randomUUID(), tenant, productId, studentId, authentication.getName());
        }
        return Map.of("studentId", studentId, "allocatedCount", productIds.size());
    }

    @GetMapping("/transport/students")
    public List<Map<String, Object>> transportStudents() {
        return jdbc.queryForList("""
            select s.id, s.user_id, s.display_name, s.email, s.roll_number,
                   coalesce(s.class_name, c.name) as class_name,
                   coalesce(s.section_name, sec.name) as section_name,
                   s.parent_contact_number, s.transport_distance_band,
                   (select count(*) from invoices i where i.tenant_id = s.tenant_id and i.student_id = s.id and i.fee_type = 'Transport fee') as invoice_count,
                   coalesce((select sum(i.amount - coalesce((select sum(p.amount) from payments p where p.invoice_id = i.id and p.tenant_id = i.tenant_id and p.status = 'SUCCESS'), 0)) from invoices i where i.tenant_id = s.tenant_id and i.student_id = s.id and i.fee_type = 'Transport fee'), 0) as balance_amount
            from students s
            left join sections sec on sec.id = s.section_id
            left join classes c on c.id = sec.class_id
            where s.tenant_id = ? and s.active = true and s.transport_enabled = true
            order by coalesce(s.class_name, c.name), coalesce(s.section_name, sec.name), s.display_name
            """, TenantContext.require());
    }

    private void requireProduct(UUID tenant, UUID productId) {
        Integer count = jdbc.queryForObject("select count(*) from facility_products where tenant_id = ? and id = ? and active = true", Integer.class, tenant, productId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Facility item not found");
    }

    private void lockProduct(UUID tenant, UUID productId) {
        boolean found = jdbc.query("select id from facility_products where tenant_id = ? and id = ? and active = true for update", rs -> { return rs.next(); }, tenant, productId);
        if (!found) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Facility item not found");
    }

    private void requireStudent(UUID tenant, UUID studentId) {
        Integer count = jdbc.queryForObject("select count(*) from students where tenant_id = ? and id = ? and active = true", Integer.class, tenant, studentId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found");
    }

    private int allocationCount(UUID tenant, UUID productId, UUID studentId) {
        Integer count = jdbc.queryForObject("select count(*) from facility_allocations where tenant_id = ? and product_id = ? and student_id = ?", Integer.class, tenant, productId, studentId);
        return count == null ? 0 : count;
    }

    private boolean isPaid(UUID tenant, UUID productId, UUID studentId) {
        Boolean paid = jdbc.query("select paid from facility_student_payments where tenant_id = ? and product_id = ? and student_id = ? for update", rs -> rs.next() && rs.getBoolean(1), tenant, productId, studentId);
        return paid;
    }

    private String category(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!CATEGORIES.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose Bag, Uniform, Identity Card, Book or Other");
        return normalized;
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record ProductRequest(@NotBlank String name, @NotBlank String category, String size, @Min(0) int quantity) {}
    public record StockRequest(@Positive int quantity) {}
    public record PaymentRequest(boolean paid) {}
    public record AllocationRequest(@NotNull UUID studentId, @Positive int quantity) {}
    public record AssetAllocationRequest(@NotNull List<UUID> productIds) {}
}
