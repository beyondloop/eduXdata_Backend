package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class StudentFeesController {
    private static final String FEE_SELECT = "select i.id, i.student_id, s.display_name as student_name, s.user_id as student_user_id, s.transport_enabled, s.transport_distance_band, i.month_start, i.academic_year, to_char(i.month_start, 'FMMonth YYYY') as month_label, i.fee_type, i.amount, i.status, coalesce((select sum(payment.amount) from payments payment where payment.invoice_id = i.id and payment.status = 'SUCCESS'), 0) as paid_amount, i.amount - coalesce((select sum(payment.amount) from payments payment where payment.invoice_id = i.id and payment.status = 'SUCCESS'), 0) as balance_amount, (select payment.payment_method from payments payment where payment.invoice_id = i.id and payment.status = 'SUCCESS' order by payment.paid_at desc limit 1) as payment_method, (select payment.provider_reference from payments payment where payment.invoice_id = i.id and payment.status = 'SUCCESS' order by payment.paid_at desc limit 1) as provider_reference ";
    private final JdbcTemplate jdbc;
    public StudentFeesController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/v1/students/{studentId}/fees")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<Map<String, Object>> list(@PathVariable UUID studentId) {
        UUID tenant = TenantContext.require();
        requireStudent(tenant, studentId);
        seedInvoicesIfMissing(tenant, studentId);
        return findFees(tenant, studentId);
    }

    @GetMapping("/api/v1/fees/me")
    @PreAuthorize("hasRole('STUDENT')")
    @Transactional
    public List<Map<String, Object>> listForStudent(@AuthenticationPrincipal Jwt jwt) {
        UUID tenant = TenantContext.require();
        UUID studentId = findStudentByUserId(tenant, jwt.getSubject());
        seedInvoicesIfMissing(tenant, studentId);
        return findFees(tenant, studentId);
    }

    @GetMapping("/api/v1/fee-collections")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
    @Transactional
    public List<Map<String, Object>> feeCollections(@RequestParam(required = false) String academicYear) {
        UUID tenant = TenantContext.require();
        if (academicYear == null || academicYear.isBlank()) {
            var years = jdbc.queryForList("select name from academic_years where tenant_id = ? and active order by starts_on desc limit 1", String.class, tenant);
            academicYear = years.isEmpty() ? "" : years.get(0);
        }
        final String selectedYear = academicYear;
        jdbc.queryForList("select id from students where tenant_id = ? and active = true", UUID.class, tenant).forEach(studentId -> seedInvoicesIfMissing(tenant, studentId, selectedYear));
        return jdbc.queryForList("""
            select s.id as student_id, s.display_name as student_name, s.user_id as student_user_id, s.roll_number, s.class_name, s.section_name,
              count(i.id) as invoice_count, coalesce(sum(i.amount), 0) as total_amount,
              coalesce(sum((select sum(p.amount) from payments p where p.invoice_id = i.id and p.status = 'SUCCESS')), 0) as paid_amount,
              coalesce(sum(i.amount - coalesce((select sum(p.amount) from payments p where p.invoice_id = i.id and p.status = 'SUCCESS'), 0)), 0) as pending_amount,
              coalesce((select sum(ri.amount - ri.applied_to_invoices) from fees_collection r join fee_receipt_items ri on ri.receipt_id = r.id where r.tenant_id = s.tenant_id and r.student_id = s.id and r.academic_year = ?), 0) as receipt_collected_amount
            from students s left join invoices i on i.student_id = s.id and i.tenant_id = s.tenant_id and i.month_start is not null and i.academic_year = ?
            where s.tenant_id = ? and s.active = true
            group by s.id, s.display_name, s.user_id, s.roll_number, s.class_name, s.section_name
            order by s.class_name, s.section_name, s.display_name
            """, academicYear, academicYear, tenant);
    }

    @GetMapping("/api/v1/fees/me/structure")
    @PreAuthorize("hasRole('STUDENT')")
    public Map<String, Object> myStructure(@AuthenticationPrincipal Jwt jwt) {
        UUID tenant = TenantContext.require();
        UUID studentId = findStudentByUserId(tenant, jwt.getSubject());
        return jdbc.queryForMap("select s.class_name, f.id is not null as configured, f.academic_fee as tuition_fee, f.library_fee, f.readmission_fee from students s left join student_fee_structures f on f.tenant_id = s.tenant_id and f.class_name = s.class_name where s.id = ? and s.tenant_id = ?", studentId, tenant);
    }

    @PostMapping("/api/v1/students/{studentId}/fees/{invoiceId}/payments")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String, Object> recordAdminPayment(@PathVariable UUID studentId, @PathVariable UUID invoiceId, @Valid @RequestBody PaymentRequest request) {
        UUID tenant = TenantContext.require();
        requireStudent(tenant, studentId);
        return recordPayment(tenant, studentId, invoiceId, request);
    }

    @PostMapping("/api/v1/fees/me/{invoiceId}/payments")
    @PreAuthorize("hasRole('STUDENT')")
    @Transactional
    public Map<String, Object> recordStudentPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID invoiceId, @Valid @RequestBody PaymentRequest request) {
        UUID tenant = TenantContext.require();
        UUID studentId = findStudentByUserId(tenant, jwt.getSubject());
        return recordPayment(tenant, studentId, invoiceId, request);
    }

    private Map<String, Object> recordPayment(UUID tenant, UUID studentId, UUID invoiceId, PaymentRequest request) {
        Map<String, Object> invoice = findInvoice(tenant, studentId, invoiceId);
        if ("PAID".equals(invoice.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This fee has already been paid");
        BigDecimal balance = decimal(invoice.get("balance_amount"));
        BigDecimal amount = request.amount() == null ? balance : request.amount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(balance) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount between ₹1 and the remaining balance");
        UUID paymentId = UUID.randomUUID();
        String providerReference = paymentReference(invoice, request);
        jdbc.update("insert into payments(id, tenant_id, invoice_id, amount, provider_reference, payment_method, status, paid_at) values (?, ?, ?, ?, ?, ?, 'SUCCESS', ?)", paymentId, tenant, invoiceId, amount, providerReference, request.paymentMethod(), OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update("update invoices set status = case when ? >= amount then 'PAID' else 'PARTIAL' end where id = ? and tenant_id = ? and student_id = ?", amount.add(decimal(invoice.get("paid_amount"))), invoiceId, tenant, studentId);
        return findInvoice(tenant, studentId, invoiceId);
    }

    private String paymentReference(Map<String, Object> invoice, PaymentRequest request) {
        String suppliedReference = request.reference() == null ? "" : request.reference().trim();
        String paymentLabel = "CASH".equalsIgnoreCase(request.paymentMethod()) ? "Cash payment" : request.paymentMethod() + " payment";
        String studentReference = "Student: " + invoice.get("student_name") + " · " + invoice.get("student_user_id");
        String combined = (suppliedReference.isBlank() ? paymentLabel : suppliedReference) + " · " + studentReference;
        return combined.length() <= 120 ? combined : combined.substring(0, 120);
    }

    private List<Map<String, Object>> findFees(UUID tenant, UUID studentId) {
        return jdbc.queryForList(FEE_SELECT + "from invoices i join students s on s.id = i.student_id where i.tenant_id = ? and i.student_id = ? and i.month_start is not null and (i.fee_type <> 'Transport fee' or s.transport_enabled = true or i.status = 'PAID') order by i.month_start, i.fee_type", tenant, studentId);
    }

    private Map<String, Object> findInvoice(UUID tenant, UUID studentId, UUID invoiceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(FEE_SELECT + "from invoices i join students s on s.id = i.student_id where i.id = ? and i.tenant_id = ? and i.student_id = ? and (i.fee_type <> 'Transport fee' or s.transport_enabled = true or i.status = 'PAID')", invoiceId, tenant, studentId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fee record not found");
        return rows.get(0);
    }

    private UUID findStudentByUserId(UUID tenant, String userId) {
        List<UUID> ids = jdbc.query("select id from students where tenant_id = ? and user_id = ? and active = true", (rs, row) -> rs.getObject("id", UUID.class), tenant, userId);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student account not found");
        return ids.get(0);
    }

    private void requireStudent(UUID tenant, UUID studentId) {
        Integer count = jdbc.queryForObject("select count(*) from students where id = ? and tenant_id = ? and active = true", Integer.class, studentId, tenant);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found");
    }

    private void seedInvoicesIfMissing(UUID tenant, UUID studentId) {
        seedInvoicesIfMissing(tenant, studentId, null);
    }

    private void seedInvoicesIfMissing(UUID tenant, UUID studentId, String academicYear) {
        var structures = jdbc.queryForList("select s.class_name, s.transport_enabled, s.transport_distance_band, f.academic_fee, f.library_fee, f.readmission_fee, f.transport_0_5_fee, f.transport_5_10_fee, f.transport_10_15_fee, f.transport_15_20_fee, f.transport_20_above_fee from students s join student_fee_structures f on f.tenant_id = s.tenant_id and f.class_name = s.class_name where s.id = ? and s.tenant_id = ?", studentId, tenant);
        if (structures.isEmpty()) return;
        var years = academicYear == null
            ? jdbc.queryForList("select name, starts_on, ends_on from academic_years where tenant_id = ? and active order by starts_on desc limit 1", tenant)
            : jdbc.queryForList("select name, starts_on, ends_on from academic_years where tenant_id = ? and name = ? order by starts_on desc limit 1", tenant, academicYear);
        if (years.isEmpty()) return;
        Map<String, Object> structure = structures.get(0), year = years.get(0);
        BigDecimal academicFee = decimal(structure.get("academic_fee"));
        BigDecimal libraryFee = decimal(structure.get("library_fee"));
        BigDecimal readmissionFee = decimal(structure.get("readmission_fee"));
        boolean transportEnabled = Boolean.TRUE.equals(structure.get("transport_enabled"));
        String transportBand = String.valueOf(structure.get("transport_distance_band"));
        BigDecimal transportFee = switch (transportBand) { case "5-10" -> decimal(structure.get("transport_5_10_fee")); case "10-15" -> decimal(structure.get("transport_10_15_fee")); case "15-20" -> decimal(structure.get("transport_15_20_fee")); case "20-above" -> decimal(structure.get("transport_20_above_fee")); default -> decimal(structure.get("transport_0_5_fee")); };
        YearMonth start = YearMonth.from(((java.sql.Date) year.get("starts_on")).toLocalDate());
        YearMonth end = YearMonth.from(((java.sql.Date) year.get("ends_on")).toLocalDate());
        for (YearMonth value = start; !value.isAfter(end); value = value.plusMonths(1)) {
            insertInvoice(tenant, studentId, value, "Tuition fee", academicFee, year.get("name").toString());
            insertInvoice(tenant, studentId, value, "Library fee", libraryFee, year.get("name").toString());
            if (value.equals(start)) insertInvoice(tenant, studentId, value, "Re-admission fee", readmissionFee, year.get("name").toString());
            if (transportEnabled) insertInvoice(tenant, studentId, value, "Transport fee", transportFee, year.get("name").toString());
        }
    }

    private BigDecimal decimal(Object value) { return value instanceof BigDecimal amount ? amount : new BigDecimal(value.toString()); }

    private void insertInvoice(UUID tenant, UUID studentId, YearMonth month, String type, BigDecimal amount, String academicYear) {
        String number = "FEE-" + studentId.toString().substring(0, 8) + "-" + month + "-" + type.replace(" ", "").toUpperCase();
        jdbc.update("insert into invoices(id, tenant_id, student_id, invoice_number, amount, status, month_start, fee_type, academic_year) values (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?) on conflict do nothing", UUID.randomUUID(), tenant, studentId, number, amount, month.atDay(1), type, academicYear);
    }

    public record PaymentRequest(@NotBlank String paymentMethod, String reference, @DecimalMin("0.01") BigDecimal amount) {}
}
