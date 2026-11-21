package com.edura.schoolos.finance;

import com.edura.schoolos.attendance.LeavePolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

final class PayrollCalculation {
    static Map<String, Object> payableDays(List<Map<String, Object>> history,
                                          List<Map<String, Object>> attendance, YearMonth month) {
        var present = new java.util.HashSet<LocalDate>();
        for (var row : attendance) if ("PRESENT".equals(row.get("status")))
            present.add(LocalDate.parse(row.get("attendance_date").toString()));
        var paidLeave = new java.util.HashSet<LocalDate>();
        var emergency = new java.util.HashSet<LocalDate>();
        var unpaid = new java.util.HashSet<LocalDate>();
        var approved = history.stream().filter(row -> "APPROVED".equals(row.get("status"))).toList();
        for (var leave : LeavePolicy.withPayBreakdown(approved)) {
            var unpaidForLeave = new java.util.HashSet<LocalDate>();
            for (Object item : (List<?>) leave.get("unpaid_periods")) {
                Map<?, ?> period = (Map<?, ?>) item;
                addDates(unpaidForLeave, period.get("from"), period.get("to"), month);
            }
            var paidForLeave = new java.util.HashSet<LocalDate>();
            addDates(paidForLeave, leave.get("from_date"), leave.get("to_date"), month);
            paidForLeave.removeAll(unpaidForLeave);
            unpaid.addAll(unpaidForLeave);
            if ("EMERGENCY".equals(leave.get("leave_type"))) emergency.addAll(paidForLeave);
            else paidLeave.addAll(paidForLeave);
        }
        paidLeave.removeAll(present);
        emergency.removeAll(present);
        emergency.removeAll(paidLeave);
        unpaid.removeAll(present);
        unpaid.removeAll(paidLeave);
        unpaid.removeAll(emergency);
        int payable = attendance.isEmpty() && paidLeave.isEmpty() && emergency.isEmpty()
            ? month.lengthOfMonth() - unpaid.size() : present.size() + paidLeave.size() + emergency.size();
        return Map.of("attendance_records", attendance.size(), "present_days", present.size(),
            "paid_leave_days", paidLeave.size(), "emergency_days", emergency.size(),
            "leave_lwp_days", unpaid.size(), "paid_days", payable);
    }

    private static void addDates(java.util.Set<LocalDate> dates, Object fromValue, Object toValue, YearMonth month) {
        LocalDate from = LocalDate.parse(fromValue.toString()), to = LocalDate.parse(toValue.toString());
        LocalDate start = from.isAfter(month.atDay(1)) ? from : month.atDay(1);
        LocalDate end = to.isBefore(month.atEndOfMonth()) ? to : month.atEndOfMonth();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) dates.add(day);
    }

    record Amounts(int totalDays, int lwpDays, BigDecimal gross, BigDecimal lwpDeduction,
                   BigDecimal totalDeductions, BigDecimal net) {}

    static int unpaidDays(List<Map<String, Object>> history, YearMonth month) {
        var approved = history.stream().filter(row -> "APPROVED".equals(row.get("status"))).toList();
        int days = 0;
        for (var leave : LeavePolicy.withPayBreakdown(approved)) {
            for (Object item : (List<?>) leave.get("unpaid_periods")) {
                Map<?, ?> period = (Map<?, ?>) item;
                LocalDate from = LocalDate.parse(period.get("from").toString());
                LocalDate to = LocalDate.parse(period.get("to").toString());
                LocalDate start = from.isAfter(month.atDay(1)) ? from : month.atDay(1);
                LocalDate end = to.isBefore(month.atEndOfMonth()) ? to : month.atEndOfMonth();
                if (!end.isBefore(start)) days += (int) ChronoUnit.DAYS.between(start, end) + 1;
            }
        }
        return days;
    }

    static Amounts calculatePaidDays(YearMonth month, int paidDays, BigDecimal basic, BigDecimal allowances,
                                      BigDecimal pf, BigDecimal other) {
        int total = month.lengthOfMonth();
        if (paidDays < 0 || paidDays > total) throw new IllegalArgumentException("Invalid payable day count");
        BigDecimal earnedBasic = basic.multiply(BigDecimal.valueOf(paidDays))
            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        BigDecimal deduction = basic.subtract(earnedBasic).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross = basic.add(allowances).setScale(2, RoundingMode.HALF_UP);
        BigDecimal deductions = deduction.add(pf).add(other).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(deductions);
        if (net.signum() < 0) throw new IllegalArgumentException("Deductions exceed salary for the payable days. Adjust payroll before generating.");
        return new Amounts(total, total - paidDays, gross, deduction, deductions, net);
    }

    static Amounts calculate(YearMonth month, int lwpDays, BigDecimal basic, BigDecimal allowances,
                             BigDecimal pf, BigDecimal other) {
        int total = month.lengthOfMonth();
        if (lwpDays < 0 || lwpDays > total) throw new IllegalArgumentException("Invalid LWP day count");
        BigDecimal gross = basic.add(allowances).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lwp = gross.multiply(BigDecimal.valueOf(lwpDays)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        BigDecimal deductions = lwp.add(pf).add(other).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(deductions);
        if (net.signum() < 0) throw new IllegalArgumentException("Deductions exceed salary after LWP. Adjust this teacher's payroll before generating.");
        return new Amounts(total, lwpDays, gross, lwp, deductions, net);
    }
}
