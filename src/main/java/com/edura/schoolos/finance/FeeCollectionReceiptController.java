package com.edura.schoolos.finance;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fee-collections")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class FeeCollectionReceiptController {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Kolkata");
    private static final List<FeeCategory> CATEGORIES = List.of(
        new FeeCategory("readmission_fee", "Admission / Readmission Fee"),
        new FeeCategory("academic_fee", "Tuition Fee"),
        new FeeCategory("development_fee", "Development Fee"),
        new FeeCategory("examination_fee", "Examination Fee"),
        new FeeCategory("game_fee", "Game Fee"),
        new FeeCategory("library_fee", "Library Fee"),
        new FeeCategory("magazine_fee", "Magazine Fee"),
        new FeeCategory("banavihar_fee", "Banavihar Fee"),
        new FeeCategory("medicine_fee", "Medicine Fee"),
        new FeeCategory("annual_function_fee", "Annual Function"),
        new FeeCategory("puja_festival_fee", "Puja & Festival"),
        new FeeCategory("prantiya_nidhi_fee", "Prantiya Nidhi"),
        new FeeCategory("sanskar_kendra_fee", "Sanskar Kendra"),
        new FeeCategory("late_fee", "Late Fee"),
        new FeeCategory("misc_fee", "Misc. Fee")
    );

    private final JdbcTemplate jdbc;

    public FeeCollectionReceiptController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/students/{studentId}/history")
    public ReceiptHistoryResponse receiptHistory(@PathVariable UUID studentId) {
        UUID tenant = TenantContext.require();
        Map<UUID, ReceiptHistoryAccumulator> history = new LinkedHashMap<>();
        jdbc.query("""
            select r.id, r.receipt_number, r.academic_year, r.period_start, r.period_end, r.total_amount, r.payment_method, r.collected_by, r.collected_at,
                t.name as school_name, s.display_name, s.admission_number, s.father_name, s.class_name, s.section_name, s.roll_number,
                i.fee_type, i.amount as item_amount
            from fees_collection r
            join students s on s.id = r.student_id and s.tenant_id = r.tenant_id
            join tenants t on t.id = r.tenant_id
            left join fee_receipt_items i on i.receipt_id = r.id
            where r.tenant_id = ? and r.student_id = ?
            order by r.collected_at desc, r.receipt_number desc, i.fee_type
            """, rs -> {
            while (rs.next()) {
                UUID id = rs.getObject("id", UUID.class);
                ReceiptHistoryAccumulator item = history.get(id);
                if (item == null) {
                    item = new ReceiptHistoryAccumulator(id, rs.getString("receipt_number"), rs.getString("academic_year"),
                        rs.getString("school_name"), rs.getString("display_name"), rs.getString("admission_number"),
                        rs.getString("father_name"), rs.getString("class_name"), rs.getString("section_name"),
                        rs.getString("roll_number"), rs.getString("payment_method"), rs.getString("collected_by"),
                        rs.getObject("collected_at", OffsetDateTime.class), rs.getBigDecimal("total_amount"),
                        rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class));
                    history.put(id, item);
                }
                String feeType = rs.getString("fee_type");
                if (feeType != null) item.items().add(new FeeOption(feeType, rs.getBigDecimal("item_amount")));
            }
            return null;
        }, tenant, studentId);
        List<FeeReceipt> receipts = history.values().stream().map(item -> new FeeReceipt(item.id(), item.receiptNumber(), item.collectedAt(), item.academicYear(),
            item.schoolName(), new ReceiptStudent(studentId, item.name(), item.admissionNumber(), item.fatherName(), item.className(),
            item.sectionName(), item.rollNumber()), item.totalAmount(), item.paymentMethod(), item.collectingOfficer(),
            item.periodStart(), item.periodEnd(), item.items())).toList();
        List<StudentPayment> payments = jdbc.query("""
            select p.id, p.amount, p.payment_method, p.provider_reference, p.paid_at, i.fee_type,
                to_char(i.month_start, 'FMMonth YYYY') as month_label, i.academic_year
            from payments p join invoices i on i.id = p.invoice_id and i.tenant_id = p.tenant_id
            where p.tenant_id = ? and i.student_id = ? and p.status = 'SUCCESS'
                and coalesce(p.provider_reference, '') not like 'Receipt %'
            order by p.paid_at desc
            """, (rs, rowNum) -> new StudentPayment(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"),
                rs.getString("payment_method"), rs.getString("provider_reference"), rs.getObject("paid_at", OffsetDateTime.class),
                rs.getString("fee_type"), rs.getString("month_label"), rs.getString("academic_year")), tenant, studentId);
        return new ReceiptHistoryResponse(receipts, payments);
    }

    @GetMapping("/students/{studentId}/receipt-options")
    public ReceiptOptions receiptOptions(@PathVariable UUID studentId, @RequestParam String academicYear) {
        UUID tenant = TenantContext.require();
        requireAcademicYear(tenant, academicYear);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select s.id, s.display_name, s.admission_number, s.user_id, s.father_name, s.class_name, s.section_name, s.roll_number,
                f.academic_fee, f.library_fee, f.readmission_fee, f.development_fee, f.examination_fee, f.game_fee,
                f.magazine_fee, f.banavihar_fee, f.medicine_fee, f.annual_function_fee, f.puja_festival_fee,
                f.prantiya_nidhi_fee, f.sanskar_kendra_fee, f.late_fee, f.misc_fee
            from students s left join student_fee_structures f on f.tenant_id = s.tenant_id and f.class_name = s.class_name
            where s.tenant_id = ? and s.id = ? and s.active = true
            """, tenant, studentId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found");
        Map<String, Object> row = rows.get(0);
        var student = new ReceiptStudent(studentId, text(row.get("display_name")), text(row.get("admission_number")),
            text(row.get("father_name")), text(row.get("class_name")), text(row.get("section_name")), text(row.get("roll_number")));
        List<String> paidFeeTypes = jdbc.queryForList("select distinct i.fee_type from fees_collection r join fee_receipt_items i on i.receipt_id = r.id where r.tenant_id = ? and r.student_id = ? and r.academic_year = ?", String.class, tenant, studentId, academicYear);
        List<FeeOption> items = CATEGORIES.stream()
            .map(category -> new FeeOption(category.label(), amount(row.get(category.column())), paidFeeTypes.contains(category.label())))
            .toList();
        return new ReceiptOptions(student, academicYear, items);
    }

    @PostMapping("/receipts")
    @Transactional
    public FeeReceipt collect(@Valid @RequestBody CollectRequest request, Authentication authentication) {
        UUID tenant = TenantContext.require();
        requireAcademicYear(tenant, request.academicYear());
        if (request.periodStart().isAfter(request.periodEnd()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Period start date must be on or before the end date");
        String paymentMethod = request.paymentMethod().trim().toUpperCase();
        if (!List.of("CASH", "UPI", "CHEQUE", "CARD").contains(paymentMethod))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose Cash, UPI, Cheque or Card");

        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                select s.display_name, s.admission_number, s.user_id, s.father_name, s.class_name, s.section_name, s.roll_number,
                    t.name as school_name, f.academic_fee, f.library_fee, f.readmission_fee, f.development_fee,
                    f.examination_fee, f.game_fee, f.magazine_fee, f.banavihar_fee, f.medicine_fee,
                    f.annual_function_fee, f.puja_festival_fee, f.prantiya_nidhi_fee, f.sanskar_kendra_fee,
                    f.late_fee, f.misc_fee
                from students s join tenants t on t.id = s.tenant_id
                left join student_fee_structures f on f.tenant_id = s.tenant_id and f.class_name = s.class_name
                where s.tenant_id = ? and s.id = ? and s.active = true
                for update of s
                """, tenant, request.studentId());
        } catch (org.springframework.dao.EmptyResultDataAccessException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found");
        }

        Map<String, FeeCategory> byLabel = new LinkedHashMap<>();
        CATEGORIES.forEach(category -> byLabel.put(category.label(), category));
        List<String> previouslyCollected = jdbc.queryForList("select distinct i.fee_type from fees_collection r join fee_receipt_items i on i.receipt_id = r.id where r.tenant_id = ? and r.student_id = ? and r.academic_year = ?", String.class, tenant, request.studentId(), request.academicYear());
        List<FeeOption> selected = new ArrayList<>();
        for (String feeType : request.feeTypes().stream().distinct().toList()) {
            FeeCategory category = byLabel.get(feeType);
            if (category == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A selected fee type is not valid");
            if (previouslyCollected.contains(feeType)) throw new ResponseStatusException(HttpStatus.CONFLICT, category.label() + " has already been collected for this academic year");
            BigDecimal feeAmount = amount(row.get(category.column()));
            if (feeAmount.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, category.label() + " has no amount in the class fee structure");
            selected.add(new FeeOption(category.label(), feeAmount));
        }
        if (selected.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one fee to collect");
        BigDecimal total = selected.stream().map(FeeOption::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID receiptId = UUID.randomUUID();
        String receiptNumber = jdbc.queryForObject("select 'RCPT-' || to_char(current_date, 'YYYYMMDD') || '-' || lpad(nextval('fee_receipt_number_seq')::text, 5, '0')", String.class);
        String collectedBy = authentication == null ? null : authentication.getName();
        OffsetDateTime collectedAt = OffsetDateTime.now(SCHOOL_ZONE);
        jdbc.update("insert into fees_collection(id, tenant_id, student_id, receipt_number, academic_year, period_start, period_end, total_amount, payment_method, collected_by, collected_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            receiptId, tenant, request.studentId(), receiptNumber, request.academicYear(), request.periodStart(), request.periodEnd(), total, paymentMethod, collectedBy, collectedAt);
        for (FeeOption item : selected) {
            BigDecimal applied = applyToInvoices(tenant, request.studentId(), request.academicYear(), item, paymentMethod, receiptNumber, collectedAt);
            jdbc.update("insert into fee_receipt_items(receipt_id, fee_type, amount, applied_to_invoices) values (?, ?, ?, ?)", receiptId, item.feeType(), item.amount(), applied);
        }
        return new FeeReceipt(receiptId, receiptNumber, collectedAt, request.academicYear(),
            text(row.get("school_name")), new ReceiptStudent(request.studentId(), text(row.get("display_name")),
            text(row.get("admission_number")), text(row.get("father_name")), text(row.get("class_name")),
            text(row.get("section_name")), text(row.get("roll_number"))), total, paymentMethod,
            authentication == null ? "" : authentication.getName(), request.periodStart(), request.periodEnd(), selected);
    }

    private void requireAcademicYear(UUID tenant, String academicYear) {
        Integer count = jdbc.queryForObject("select case when exists (select 1 from academic_years where tenant_id = ? and name = ?) or exists (select 1 from invoices where tenant_id = ? and academic_year = ?) then 1 else 0 end", Integer.class, tenant, academicYear, tenant, academicYear);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a configured academic year");
    }

    private BigDecimal applyToInvoices(UUID tenant, UUID studentId, String academicYear, FeeOption item,
            String paymentMethod, String receiptNumber, OffsetDateTime collectedAt) {
        String invoiceType = switch (item.feeType()) {
            case "Tuition Fee" -> "Tuition fee";
            case "Library Fee" -> "Library fee";
            case "Admission / Readmission Fee" -> "Re-admission fee";
            default -> null;
        };
        if (invoiceType == null) return BigDecimal.ZERO;
        BigDecimal remaining = item.amount();
        BigDecimal applied = BigDecimal.ZERO;
        List<Map<String, Object>> invoices = jdbc.queryForList("select id, amount from invoices where tenant_id = ? and student_id = ? and academic_year = ? and fee_type = ? and month_start is not null and status in ('PENDING','PARTIAL') order by month_start for update", tenant, studentId, academicYear, invoiceType);
        for (Map<String, Object> invoice : invoices) {
            if (remaining.signum() <= 0) break;
            UUID invoiceId = (UUID) invoice.get("id");
            BigDecimal invoiceAmount = amount(invoice.get("amount"));
            BigDecimal paid = jdbc.queryForObject("select coalesce(sum(amount), 0) from payments where tenant_id = ? and invoice_id = ? and status = 'SUCCESS'", BigDecimal.class, tenant, invoiceId);
            BigDecimal balance = invoiceAmount.subtract(paid == null ? BigDecimal.ZERO : paid);
            if (balance.signum() <= 0) continue;
            BigDecimal payment = remaining.min(balance);
            jdbc.update("insert into payments(id, tenant_id, invoice_id, amount, provider_reference, payment_method, status, paid_at) values (?, ?, ?, ?, ?, ?, 'SUCCESS', ?)",
                UUID.randomUUID(), tenant, invoiceId, payment, "Receipt " + receiptNumber, paymentMethod, collectedAt);
            jdbc.update("update invoices set status = case when ? >= amount then 'PAID' else 'PARTIAL' end where id = ? and tenant_id = ? and student_id = ?",
                (paid == null ? BigDecimal.ZERO : paid).add(payment), invoiceId, tenant, studentId);
            remaining = remaining.subtract(payment);
            applied = applied.add(payment);
        }
        return applied;
    }

    private static BigDecimal amount(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
    private static String text(Object value) { return value == null ? "" : value.toString(); }

    private record FeeCategory(String column, String label) {}
    private record ReceiptHistoryAccumulator(UUID id, String receiptNumber, String academicYear, String schoolName,
            String name, String admissionNumber, String fatherName, String className, String sectionName, String rollNumber,
            String paymentMethod, String collectingOfficer, OffsetDateTime collectedAt, BigDecimal totalAmount,
            LocalDate periodStart, LocalDate periodEnd, List<FeeOption> items) {
        ReceiptHistoryAccumulator(UUID id, String receiptNumber, String academicYear, String schoolName, String name,
                String admissionNumber, String fatherName, String className, String sectionName, String rollNumber,
                String paymentMethod, String collectingOfficer, OffsetDateTime collectedAt, BigDecimal totalAmount,
                LocalDate periodStart, LocalDate periodEnd) {
            this(id, receiptNumber, academicYear, schoolName, name, admissionNumber, fatherName, className, sectionName,
                rollNumber, paymentMethod, collectingOfficer, collectedAt, totalAmount, periodStart, periodEnd, new ArrayList<>());
        }
    }
    public record FeeOption(String feeType, BigDecimal amount, boolean paid) {
        public FeeOption(String feeType, BigDecimal amount) { this(feeType, amount, false); }
    }
    public record ReceiptStudent(UUID id, String name, String admissionNumber, String fatherName, String className, String sectionName, String rollNumber) {}
    public record ReceiptOptions(ReceiptStudent student, String academicYear, List<FeeOption> items) {}
    public record CollectRequest(@NotNull UUID studentId, @NotBlank String academicYear, @NotEmpty List<@NotBlank String> feeTypes,
            @NotBlank String paymentMethod, @NotNull LocalDate periodStart, @NotNull LocalDate periodEnd) {}
    public record FeeReceipt(UUID id, String receiptNumber, OffsetDateTime collectedAt, String academicYear, String schoolName,
            ReceiptStudent student, BigDecimal totalAmount, String paymentMethod, String collectingOfficer,
            LocalDate periodStart, LocalDate periodEnd, List<FeeOption> items) {}
    public record StudentPayment(UUID id, BigDecimal amount, String paymentMethod, String reference, OffsetDateTime paidAt,
            String feeType, String monthLabel, String academicYear) {}
    public record ReceiptHistoryResponse(List<FeeReceipt> receipts, List<StudentPayment> payments) {}
}
