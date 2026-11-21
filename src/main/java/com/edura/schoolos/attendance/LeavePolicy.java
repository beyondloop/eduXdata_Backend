package com.edura.schoolos.attendance;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;

public final class LeavePolicy {
    // Allocate in submission order, reserving pending days and releasing rejected days.
    // Recompute from the complete history so existing records follow the same policy.
    public static List<Map<String, Object>> withPayBreakdown(List<Map<String, Object>> rows) {
        var ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing((Map<String, Object> row) -> row.get("submitted_at").toString())
            .thenComparing(row -> row.get("id").toString()));
        Map<String, Long> usage = new HashMap<>();
        Map<Object, Map<String, Object>> calculated = new HashMap<>();
        for (var row : ordered) {
            LocalDate from = LocalDate.parse(row.get("from_date").toString());
            LocalDate to = LocalDate.parse(row.get("to_date").toString());
            long paid = 0, unpaid = 0;
            var unpaidPeriods = new ArrayList<Map<String, String>>();
            if (!"REJECTED".equals(row.get("status"))) {
                for (int year = from.getYear(); year <= to.getYear(); year++) {
                    String key = row.get("teacher_id") + ":" + row.get("leave_type") + ":" + year;
                    long requested = days(from, to, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
                    long used = usage.getOrDefault(key, 0L);
                    long allowed = "EMERGENCY".equals(row.get("leave_type")) ? requested : Math.min(requested, Math.max(0, 5 - used));
                    paid += allowed;
                    unpaid += requested - allowed;
                    if (requested > allowed) {
                        LocalDate start = from.isAfter(LocalDate.of(year, 1, 1)) ? from : LocalDate.of(year, 1, 1);
                        LocalDate end = to.isBefore(LocalDate.of(year, 12, 31)) ? to : LocalDate.of(year, 12, 31);
                        unpaidPeriods.add(Map.of("from", start.plusDays(allowed).toString(), "to", end.toString()));
                    }
                    usage.put(key, used + requested);
                }
            }
            var result = new HashMap<>(row);
            result.put("paid_days", paid);
            result.put("unpaid_days", unpaid);
            result.put("unpaid_periods", unpaidPeriods);
            calculated.put(row.get("id"), result);
        }
        return rows.stream().map(row -> calculated.get(row.get("id"))).toList();
    }
    record Period(String type, LocalDate from, LocalDate to) {}
    static boolean emergencyAvailable(LocalDate from, LocalDate to, List<Period> history) {
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            for (String type : List.of("SICK", "CASUAL")) {
                long used = 0;
                for (Period period : history) if (period.type().equals(type))
                    used += days(period.from(), period.to(), LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
                if (used < 5) return false;
            }
        }
        return !to.isBefore(from);
    }
    static boolean proofRequired(String type, LocalDate from, LocalDate to, List<Period> history) {
        if ("EMERGENCY".equals(type)) return true;
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            LocalDate start = LocalDate.of(year, 1, 1), end = LocalDate.of(year, 12, 31);
            long used = days(from, to, start, end);
            for (Period period : history) if (period.type().equals(type)) used += days(period.from(), period.to(), start, end);
            if (used > 5) return true;
        }
        // Adjacent requests count as one continuous absence, even across leave types.
        LocalDate first = from, last = to;
        boolean changed;
        do {
            changed = false;
            for (Period period : history) {
                if (!period.from().isAfter(last.plusDays(1)) && !period.to().isBefore(first.minusDays(1))) {
                    if (period.from().isBefore(first)) { first = period.from(); changed = true; }
                    if (period.to().isAfter(last)) { last = period.to(); changed = true; }
                }
            }
        } while (changed);
        return ChronoUnit.DAYS.between(first, last) + 1 > 2;
    }
    private static long days(LocalDate from, LocalDate to, LocalDate start, LocalDate end) {
        LocalDate first = from.isAfter(start) ? from : start, last = to.isBefore(end) ? to : end;
        return last.isBefore(first) ? 0 : ChronoUnit.DAYS.between(first, last) + 1;
    }
}
