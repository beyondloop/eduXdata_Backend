package com.edura.schoolos.finance;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class FinancialStructureController {
    private final JdbcTemplate jdbc;

    public FinancialStructureController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/student-fee-structures")
    public List<Map<String, Object>> feeStructures() {
        UUID tenant = TenantContext.require();
        return jdbc.queryForList("select * from student_fee_structures where tenant_id = ? order by class_name", tenant);
    }

    @PostMapping("/student-fee-structures")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String, Object> saveFeeStructure(@Valid @RequestBody FeeStructureRequest request) {
        UUID tenant = TenantContext.require();
        if (jdbc.queryForObject("select count(*) from classes where tenant_id = ? and name = ? and active", Integer.class, tenant, request.className()) == 0)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Choose an active class from your school");
        jdbc.update("""
            insert into student_fee_structures(tenant_id, class_name, admission_fee, academic_fee, library_fee, readmission_fee,
                development_fee, examination_fee, game_fee, magazine_fee, banavihar_fee, medicine_fee,
                annual_function_fee, puja_festival_fee, prantiya_nidhi_fee, sanskar_kendra_fee, late_fee, misc_fee, transport_fee, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            on conflict (tenant_id, class_name) do update set
                admission_fee = excluded.admission_fee, academic_fee = excluded.academic_fee, library_fee = excluded.library_fee, readmission_fee = excluded.readmission_fee,
                development_fee = excluded.development_fee, examination_fee = excluded.examination_fee, game_fee = excluded.game_fee,
                magazine_fee = excluded.magazine_fee, banavihar_fee = excluded.banavihar_fee, medicine_fee = excluded.medicine_fee,
                annual_function_fee = excluded.annual_function_fee, puja_festival_fee = excluded.puja_festival_fee,
                prantiya_nidhi_fee = excluded.prantiya_nidhi_fee, sanskar_kendra_fee = excluded.sanskar_kendra_fee,
                late_fee = excluded.late_fee, misc_fee = excluded.misc_fee, transport_fee = excluded.transport_fee, updated_at = now()
            """, tenant, request.className(), fee(request.admissionFee()), request.academicFee(), request.libraryFee(), request.readmissionFee(),
            fee(request.developmentFee()), fee(request.examinationFee()), fee(request.gameFee()), fee(request.magazineFee()),
            fee(request.banaviharFee()), fee(request.medicineFee()), fee(request.annualFunctionFee()), fee(request.pujaFestivalFee()),
            fee(request.prantiyaNidhiFee()), fee(request.sanskarKendraFee()), fee(request.lateFee()), fee(request.miscFee()), fee(request.transportFee()));
        jdbc.update("update invoices i set amount = case when i.fee_type = 'Tuition fee' then ? when i.fee_type = 'Library fee' then ? when i.fee_type = 'Re-admission fee' then ? else i.amount end from students s where i.student_id = s.id and i.tenant_id = ? and s.class_name = ? and i.status in ('PENDING', 'PARTIAL') and i.fee_type in ('Tuition fee', 'Library fee', 'Re-admission fee')", request.academicFee(), request.libraryFee(), request.readmissionFee(), tenant, request.className());
        return jdbc.queryForMap("select * from student_fee_structures where tenant_id = ? and class_name = ?", tenant, request.className());
    }

    @GetMapping("/teacher-salary-structures")
    public List<Map<String, Object>> salaryStructures() {
        UUID tenant = TenantContext.require();
        return jdbc.queryForList("select t.id as teacher_id, t.display_name as teacher_name, t.teacher_id as teacher_code, coalesce(s.basic_salary, 0) as basic_salary, coalesce(s.allowances, 0) as allowances, coalesce(s.deductions, 0) as deductions, coalesce(s.basic_salary, 0) + coalesce(s.allowances, 0) - coalesce(s.deductions, 0) as net_salary, s.updated_at from teachers t left join teacher_salary_structures s on s.teacher_id = t.id and s.tenant_id = t.tenant_id where t.tenant_id = ? and t.active = true order by t.display_name", tenant);
    }

    @PostMapping("/teacher-salary-structures")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String, Object> saveSalaryStructure(@Valid @RequestBody SalaryStructureRequest request) {
        UUID tenant = TenantContext.require();
        Integer exists = jdbc.queryForObject("select count(*) from teachers where id = ? and tenant_id = ? and active = true", Integer.class, request.teacherId(), tenant);
        if (exists == null || exists == 0) throw new IllegalArgumentException("Teacher not found");
        jdbc.update("insert into teacher_salary_structures(tenant_id, teacher_id, basic_salary, allowances, deductions, updated_at) values (?, ?, ?, ?, ?, now()) on conflict (tenant_id, teacher_id) do update set basic_salary = excluded.basic_salary, allowances = excluded.allowances, deductions = excluded.deductions, updated_at = now()", tenant, request.teacherId(), request.basicSalary(), request.allowances(), request.deductions());
        return jdbc.queryForMap("select t.id as teacher_id, t.display_name as teacher_name, t.teacher_id as teacher_code, s.basic_salary, s.allowances, s.deductions, s.basic_salary + s.allowances - s.deductions as net_salary, s.updated_at from teacher_salary_structures s join teachers t on t.id = s.teacher_id where s.tenant_id = ? and s.teacher_id = ?", tenant, request.teacherId());
    }

    private BigDecimal fee(BigDecimal amount) { return amount == null ? BigDecimal.ZERO : amount; }

    public record FeeStructureRequest(@NotBlank String className, @DecimalMin("0") BigDecimal admissionFee, @NotNull @DecimalMin("0") BigDecimal academicFee,
            @NotNull @DecimalMin("0") BigDecimal libraryFee, @NotNull @DecimalMin("0") BigDecimal readmissionFee,
            @DecimalMin("0") BigDecimal developmentFee, @DecimalMin("0") BigDecimal examinationFee,
            @DecimalMin("0") BigDecimal gameFee, @DecimalMin("0") BigDecimal magazineFee,
            @DecimalMin("0") BigDecimal banaviharFee, @DecimalMin("0") BigDecimal medicineFee,
            @DecimalMin("0") BigDecimal annualFunctionFee, @DecimalMin("0") BigDecimal pujaFestivalFee,
            @DecimalMin("0") BigDecimal prantiyaNidhiFee, @DecimalMin("0") BigDecimal sanskarKendraFee,
            @DecimalMin("0") BigDecimal lateFee, @DecimalMin("0") BigDecimal miscFee, @DecimalMin("0") BigDecimal transportFee) {
        public FeeStructureRequest(String className, BigDecimal academicFee, BigDecimal libraryFee, BigDecimal readmissionFee) {
            this(className, BigDecimal.ZERO, academicFee, libraryFee, readmissionFee, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
    public record SalaryStructureRequest(@NotNull UUID teacherId, @NotNull @DecimalMin("0") BigDecimal basicSalary, @NotNull @DecimalMin("0") BigDecimal allowances, @NotNull @DecimalMin("0") BigDecimal deductions) {}
}
