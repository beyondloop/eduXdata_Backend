package com.edura.schoolos.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PayrollCalculationTest {
    private Map<String, Object> leave(String id, String type, String from, String to, String status) {
        return Map.of("id", id, "teacher_id", "teacher", "leave_type", type, "from_date", from, "to_date", to, "status", status, "submitted_at", id);
    }
    @Test void payableDaysIncludeRemainingYearlyLeaveWithoutDoubleCountingAttendance() {
        var history = List.of(leave("1", "CASUAL", "2026-01-01", "2026-01-04", "APPROVED"),
            leave("2", "CASUAL", "2026-09-01", "2026-09-03", "APPROVED"),
            leave("3", "SICK", "2026-09-04", "2026-09-09", "APPROVED"),
            leave("4", "SICK", "2026-09-10", "2026-09-11", "PENDING"));
        List<Map<String, Object>> attendance = List.of(
            Map.of("attendance_date", "2026-09-01", "status", "PRESENT"),
            Map.of("attendance_date", "2026-09-12", "status", "PRESENT"));
        var days = PayrollCalculation.payableDays(history, attendance, YearMonth.of(2026, 9));
        assertEquals(7, days.get("paid_days"));
        assertEquals(5, days.get("paid_leave_days"));
        assertEquals(3, days.get("leave_lwp_days"));
        assertEquals(0, PayrollCalculation.payableDays(history, List.of(), YearMonth.of(2026, 8)).get("leave_lwp_days"));
    }
    @Test void exhaustedAllowanceDeductsLwpEvenWithoutAttendanceRecords() {
        var history = List.of(leave("1", "SICK", "2026-01-01", "2026-01-05", "APPROVED"),
            leave("2", "SICK", "2026-09-01", "2026-09-02", "APPROVED"),
            leave("3", "SICK", "2027-01-01", "2027-01-02", "APPROVED"));
        assertEquals(28, PayrollCalculation.payableDays(history, List.of(), YearMonth.of(2026, 9)).get("paid_days"));
        var nextYear = PayrollCalculation.payableDays(history, List.of(), YearMonth.of(2027, 1));
        assertEquals(2, nextYear.get("paid_leave_days"));
        assertEquals(0, nextYear.get("leave_lwp_days"));
    }
    @Test void proratesOnlyBasicUsingPayableDays() {
        var pay = PayrollCalculation.calculatePaidDays(YearMonth.of(2026, 9), 28,
            new BigDecimal("30000"), new BigDecimal("6000"), new BigDecimal("1800"), new BigDecimal("200"));
        assertEquals(new BigDecimal("2000.00"), pay.lwpDeduction());
        assertEquals(new BigDecimal("32000.00"), pay.net());
        assertEquals(2, pay.lwpDays());
        var leap = PayrollCalculation.calculatePaidDays(YearMonth.of(2024, 2), 28,
            new BigDecimal("29000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(new BigDecimal("28000.00"), leap.net());
        assertThrows(IllegalArgumentException.class, () -> PayrollCalculation.calculatePaidDays(
            YearMonth.of(2026, 2), 29, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        var zero = PayrollCalculation.calculatePaidDays(YearMonth.of(2026, 9), 0,
            new BigDecimal("30000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(new BigDecimal("0.00"), zero.net());
    }
    @Test void deductsCalendarDayRateAndRoundsOnlyFinalAmount() {
        var pay = PayrollCalculation.calculate(YearMonth.of(2026, 9), 2, new BigDecimal("30000"), new BigDecimal("6000"), new BigDecimal("1800"), new BigDecimal("200"));
        assertEquals(30, pay.totalDays());
        assertEquals(new BigDecimal("2400.00"), pay.lwpDeduction());
        assertEquals(new BigDecimal("4400.00"), pay.totalDeductions());
        assertEquals(new BigDecimal("31600.00"), pay.net());
        var fraction = PayrollCalculation.calculate(YearMonth.of(2026, 10), 3, new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(new BigDecimal("967.74"), fraction.lwpDeduction());
    }
    @Test void splitsUnpaidTailAcrossMonthsAndResetsAllowanceAtNewYear() {
        var history = List.of(leave("1", "SICK", "2026-01-01", "2026-01-04", "APPROVED"),
            leave("2", "SICK", "2026-08-30", "2026-09-02", "APPROVED"),
            leave("3", "SICK", "2026-12-31", "2027-01-02", "APPROVED"));
        assertEquals(1, PayrollCalculation.unpaidDays(history, YearMonth.of(2026, 8)));
        assertEquals(2, PayrollCalculation.unpaidDays(history, YearMonth.of(2026, 9)));
        assertEquals(1, PayrollCalculation.unpaidDays(history, YearMonth.of(2026, 12)));
        assertEquals(0, PayrollCalculation.unpaidDays(history, YearMonth.of(2027, 1)));
    }
    @Test void ignoresRejectedAndPendingLeaveAndKeepsTypesSeparate() {
        var history = List.of(leave("1", "SICK", "2026-01-01", "2026-01-05", "REJECTED"),
            leave("2", "SICK", "2026-02-01", "2026-02-05", "PENDING"),
            leave("3", "CASUAL", "2026-03-01", "2026-03-05", "APPROVED"),
            leave("4", "SICK", "2026-09-01", "2026-09-06", "APPROVED"));
        assertEquals(1, PayrollCalculation.unpaidDays(history, YearMonth.of(2026, 9)));
    }
    @Test void handlesLeapMonthAndRejectsNegativePay() {
        var pay = PayrollCalculation.calculate(YearMonth.of(2024, 2), 29, new BigDecimal("29000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(29, pay.totalDays()); assertEquals(new BigDecimal("0.00"), pay.net());
        assertThrows(IllegalArgumentException.class, () -> PayrollCalculation.calculate(YearMonth.of(2024, 2), 29, new BigDecimal("29000"), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO));
    }
}
