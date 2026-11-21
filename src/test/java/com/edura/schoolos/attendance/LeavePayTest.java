package com.edura.schoolos.attendance;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LeavePayTest {
    private Map<String, Object> row(String id, String teacher, String type, String from, String to, String status) {
        return Map.of("id", id, "teacher_id", teacher, "leave_type", type, "from_date", from, "to_date", to,
            "status", status, "submitted_at", id);
    }
    @Test void approvedEmergencyCountsAsPaidLeave() {
        var result = LeavePolicy.withPayBreakdown(List.of(row("1", "A", "EMERGENCY", "2026-09-01", "2026-09-02", "APPROVED"))).get(0);
        assertEquals(2L, result.get("paid_days"));
        assertEquals(0L, result.get("unpaid_days"));
    }
    @Test void reservesPendingDaysAndSplitsPaidAndUnpaidInSubmissionOrder() {
        var first = row("1", "A", "SICK", "2026-01-01", "2026-01-04", "PENDING");
        var second = row("2", "A", "SICK", "2026-09-01", "2026-09-03", "PENDING");
        var result = LeavePolicy.withPayBreakdown(List.of(second, first));
        assertEquals(1L, result.get(0).get("paid_days"));
        assertEquals(2L, result.get(0).get("unpaid_days"));
        assertEquals(4L, result.get(1).get("paid_days"));
    }
    @Test void separatesTeachersTypesAndCalendarYearsAndReleasesRejectedDays() {
        var rows = List.of(
            row("1", "A", "SICK", "2026-01-01", "2026-01-05", "APPROVED"),
            row("2", "A", "CASUAL", "2026-02-01", "2026-02-05", "APPROVED"),
            row("3", "A", "SICK", "2027-02-01", "2027-02-05", "REJECTED"),
            row("4", "A", "SICK", "2026-12-31", "2027-01-02", "PENDING"),
            row("5", "B", "SICK", "2026-12-31", "2026-12-31", "PENDING"));
        var result = LeavePolicy.withPayBreakdown(rows);
        assertEquals(5L, result.get(1).get("paid_days"));
        assertEquals(0L, result.get(2).get("unpaid_days"));
        assertEquals(2L, result.get(3).get("paid_days"));
        assertEquals(1L, result.get(3).get("unpaid_days"));
        assertEquals(1L, result.get(4).get("paid_days"));
    }
}
