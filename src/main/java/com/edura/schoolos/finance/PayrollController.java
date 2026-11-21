package com.edura.schoolos.finance;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payroll")
public class PayrollController {
    private final JdbcTemplate jdbc;
    public PayrollController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/structures") @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public List<Map<String, Object>> structures() {
        return jdbc.queryForList("select p.*, t.display_name as teacher_name, t.teacher_id as employee_code, t.pf_number from teacher_payroll_structures p join teachers t on t.id = p.teacher_id and t.tenant_id = p.tenant_id where p.tenant_id = ? and t.active order by t.display_name", TenantContext.require());
    }

    @PostMapping("/structures") @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String, Object> createPayroll(@Valid @RequestBody PayrollRequest request) {
        savePayroll(request, true);
        return Map.of("teacherId", request.teacherId());
    }

    @PutMapping("/structures/{teacherId}") @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void updatePayroll(@PathVariable UUID teacherId, @Valid @RequestBody PayrollRequest request) {
        if (!teacherId.equals(request.teacherId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Teacher does not match payroll");
        savePayroll(request, false);
    }

    private void savePayroll(PayrollRequest request, boolean create) {
        var salary = request.salary();
        if (salary.pfDeduction().add(salary.otherDeductions()).compareTo(salary.basicSalary().add(salary.allowances())) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Deductions cannot exceed gross salary");
        if (salary.pfDeduction().signum() > 0 && request.pfNumber().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a PF number when applying a PF deduction");
        UUID tenant = TenantContext.require();
        jdbc.queryForObject("select name from tenants where id = ? for update", String.class, tenant);
        var teachers = jdbc.queryForList("select id from teachers where id = ? and tenant_id = ? and active for update", request.teacherId(), tenant);
        if (teachers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active teacher not found");
        int changed;
        if (create) {
            changed = jdbc.update("insert into teacher_payroll_structures(tenant_id, teacher_id, basic_salary, allowances, pf_deduction, other_deductions) values (?, ?, ?, ?, ?, ?) on conflict (tenant_id, teacher_id) do nothing", tenant, request.teacherId(), salary.basicSalary(), salary.allowances(), salary.pfDeduction(), salary.otherDeductions());
            if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Payroll already exists for this teacher. Use Edit payroll.");
        } else {
            changed = jdbc.update("update teacher_payroll_structures set basic_salary = ?, allowances = ?, pf_deduction = ?, other_deductions = ?, updated_at = now() where tenant_id = ? and teacher_id = ?", salary.basicSalary(), salary.allowances(), salary.pfDeduction(), salary.otherDeductions(), tenant, request.teacherId());
            if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payroll not found");
        }
        jdbc.update("update teachers set pf_number = ? where id = ? and tenant_id = ?", request.pfNumber().trim(), request.teacherId(), tenant);
    }

    @GetMapping("/employees") @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public List<Map<String, Object>> employees() {
        return jdbc.queryForList("select id, display_name, coalesce(nullif(teacher_id, ''), user_id) as employee_code, pf_number from teachers where tenant_id = ? and active order by display_name", TenantContext.require());
    }

    @PutMapping("/employees/{id}") @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateEmployee(@PathVariable UUID id, @Valid @RequestBody Employee request) {
        if (jdbc.update("update teachers set pf_number = ? where id = ? and tenant_id = ? and active", request.pfNumber().trim(), id, TenantContext.require()) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher not found");
    }

    @GetMapping("/slips") @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN','TEACHER')")
    public List<Map<String, Object>> slips(Authentication auth) {
        String sql = "select s.* from teacher_salary_slips s join teachers t on t.id = s.teacher_id and t.tenant_id = s.tenant_id where s.tenant_id = ?";
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            return jdbc.queryForList(sql + " order by s.salary_month desc, s.employee_name", TenantContext.require());
        return jdbc.queryForList(sql + " and t.user_id = ? order by s.salary_month desc", TenantContext.require(), auth.getName());
    }

    @DeleteMapping("/slips/{id}") @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void deleteSlip(@PathVariable UUID id) {
        UUID tenant = TenantContext.require();
        jdbc.queryForObject("select name from tenants where id = ? for update", String.class, tenant);
        if (jdbc.update("delete from teacher_salary_slips where tenant_id = ? and id = ?", tenant, id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Salary slip not found");
    }

    @DeleteMapping("/slips") @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    @Transactional
    public Map<String, Object> deleteMonth(@RequestParam String month) {
        YearMonth salaryMonth;
        try { salaryMonth = YearMonth.parse(month); }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid salary month"); }
        UUID tenant = TenantContext.require();
        jdbc.queryForObject("select name from tenants where id = ? for update", String.class, tenant);
        int deleted = jdbc.update("delete from teacher_salary_slips where tenant_id = ? and salary_month = ?", tenant, salaryMonth.atDay(1));
        return Map.of("deleted", deleted, "month", salaryMonth.toString());
    }

    @GetMapping("/days") @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public List<Map<String, Object>> days(@RequestParam String month) {
        YearMonth m;
        try { m = YearMonth.parse(month); }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid salary month"); }
        UUID tenant = TenantContext.require();
        var teachers = jdbc.queryForList("select p.teacher_id from teacher_payroll_structures p join teachers t on t.id = p.teacher_id and t.tenant_id = p.tenant_id where p.tenant_id = ? and t.active", tenant);
        var attendance = jdbc.queryForList("select teacher_id, attendance_date, status from teacher_attendance where tenant_id = ? and attendance_date between ? and ?", tenant, m.atDay(1), m.atEndOfMonth());
        var history = jdbc.queryForList("select id, teacher_id, leave_type, from_date, to_date, status, submitted_at from teacher_leave where tenant_id = ? and status = 'APPROVED'", tenant);
        return teachers.stream().map(teacher -> {
            Object id = teacher.get("teacher_id");
            var result = new java.util.HashMap<>(PayrollCalculation.payableDays(
                history.stream().filter(row -> id.equals(row.get("teacher_id"))).toList(),
                attendance.stream().filter(row -> id.equals(row.get("teacher_id"))).toList(), m));
            result.put("teacher_id", id);
            return (Map<String, Object>) result;
        }).toList();
    }
    @PostMapping("/generate") @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> generate(@Valid @RequestBody Generate request, Authentication auth) {
        YearMonth month;
        try { month = YearMonth.parse(request.month()); }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid salary month"); }
        if (month.isAfter(YearMonth.now(ZoneId.of("Asia/Kolkata"))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Future salary months cannot be generated");
        UUID tenant = TenantContext.require();
        String school = jdbc.queryForObject("select name from tenants where id = ? for update", String.class, tenant);
        String teacherSql = "select t.id, t.display_name, t.teacher_id as employee_code, t.pf_number, p.basic_salary, p.allowances, p.pf_deduction, p.other_deductions from teachers t join teacher_payroll_structures p on p.teacher_id = t.id and p.tenant_id = t.tenant_id where t.tenant_id = ? and t.active";
        var teachers = request.teacherId() == null
            ? jdbc.queryForList(teacherSql + " order by t.id for update of t, p", tenant)
            : jdbc.queryForList(teacherSql + " and t.id = ? for update of t, p", tenant, request.teacherId());
        if (teachers.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add payroll for an active teacher before generating salary slips");
        var monthlyDays = days(month.toString());
        if (request.paidDays() != null && request.paidDays().entrySet().stream().anyMatch(entry ->
            entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > month.lengthOfMonth()
            || teachers.stream().noneMatch(teacher -> teacher.get("id").equals(entry.getKey()))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payable days must belong to a selected teacher and be within the calendar month");
        int generated = 0;
        for (var teacher : teachers) {
            Integer existing = jdbc.queryForObject("select count(*) from teacher_salary_slips where tenant_id = ? and teacher_id = ? and salary_month = ?", Integer.class, tenant, teacher.get("id"), month.atDay(1));
            if (existing != null && existing > 0) continue;
            var history = jdbc.queryForList("select id, teacher_id, leave_type, from_date, to_date, status, submitted_at from teacher_leave where tenant_id = ? and teacher_id = ? and status in ('PENDING','APPROVED')", tenant, teacher.get("id"));
            // Resolve all reservations in this calendar year before snapshotting payroll.
            boolean pending = history.stream().anyMatch(row -> "PENDING".equals(row.get("status"))
                && row.get("from_date").toString().compareTo(month.getYear() + "-12-31") <= 0
                && row.get("to_date").toString().compareTo(month.getYear() + "-01-01") >= 0);
            if (pending) throw new ResponseStatusException(HttpStatus.CONFLICT, "Review pending leave for " + teacher.get("display_name") + " in " + month.getYear() + " before generating salary slips");
            BigDecimal basic = (BigDecimal) teacher.get("basic_salary"), allowances = (BigDecimal) teacher.get("allowances");
            BigDecimal pf = (BigDecimal) teacher.get("pf_deduction"), other = (BigDecimal) teacher.get("other_deductions");
            if (pf.signum() > 0 && teacher.get("pf_number").toString().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a PF number for " + teacher.get("display_name") + " before applying PF deductions");
            PayrollCalculation.Amounts amounts;
            try {
                int paidDays = monthlyDays.stream().filter(row -> row.get("teacher_id").equals(teacher.get("id")))
                    .map(row -> ((Number) row.get("paid_days")).intValue()).findFirst().orElse(month.lengthOfMonth());
                if (request.paidDays() != null) paidDays = request.paidDays().getOrDefault(teacher.get("id"), paidDays);
                amounts = PayrollCalculation.calculatePaidDays(month, paidDays, basic, allowances, pf, other);
            }
            catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, teacher.get("display_name") + ": " + failure.getMessage()); }
            jdbc.update("insert into teacher_salary_slips(id, tenant_id, teacher_id, salary_month, school_name, employee_name, employee_code, pf_number, total_days, lwp_days, paid_days, basic_salary, allowances, gross_salary, lwp_deduction, pf_deduction, other_deductions, total_deductions, net_salary, generated_by, attendance_based) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)",
                UUID.randomUUID(), tenant, teacher.get("id"), month.atDay(1), school, teacher.get("display_name"), teacher.get("employee_code"), teacher.get("pf_number"), amounts.totalDays(), amounts.lwpDays(), amounts.totalDays() - amounts.lwpDays(), basic, allowances, amounts.gross(), amounts.lwpDeduction(), pf, other, amounts.totalDeductions(), amounts.net(), auth.getName());
            generated++;
        }
        return Map.of("generated", generated, "skipped", teachers.size() - generated, "month", month.toString());
    }

    public record Structure(
        @NotNull @DecimalMin("0.01") @Digits(integer = 9, fraction = 2) BigDecimal basicSalary,
        @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 2) BigDecimal allowances,
        @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 2) BigDecimal pfDeduction,
        @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 2) BigDecimal otherDeductions) {}
    public record PayrollRequest(@NotNull UUID teacherId, @NotNull @Size(max = 60) String pfNumber, @NotNull @Valid Structure salary) {}
    public record Employee(@NotNull @Size(max = 60) String pfNumber) {}
    public record Generate(@NotNull @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String month, UUID teacherId, Map<UUID, Integer> paidDays) {
        public Generate(String month, UUID teacherId) { this(month, teacherId, null); }
        public Generate(String month) { this(month, null, null); }
    }
}
