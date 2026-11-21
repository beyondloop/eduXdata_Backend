package com.edura.schoolos.attendance;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LeavePolicyTest {
    private LocalDate date(String value) { return LocalDate.parse(value); }
    private LeavePolicy.Period period(String type, String from, String to) { return new LeavePolicy.Period(type, date(from), date(to)); }
    @Test void emergencyRequiresBothBalancesForEveryYear() {
        var sick = period("SICK", "2026-01-01", "2026-01-05");
        var casual = period("CASUAL", "2026-02-01", "2026-02-05");
        assertFalse(LeavePolicy.emergencyAvailable(date("2026-09-01"), date("2026-09-01"), List.of(sick)));
        assertTrue(LeavePolicy.emergencyAvailable(date("2026-09-01"), date("2026-09-01"), List.of(sick, casual)));
        assertFalse(LeavePolicy.emergencyAvailable(date("2026-12-31"), date("2027-01-01"), List.of(sick, casual)));
        assertTrue(LeavePolicy.proofRequired("EMERGENCY", date("2026-09-01"), date("2026-09-01"), List.of(sick, casual)));
    }
    @Test void twoDaysNeedNoProofButThreeDo() {
        assertFalse(LeavePolicy.proofRequired("SICK", date("2026-09-01"), date("2026-09-02"), List.of()));
        assertTrue(LeavePolicy.proofRequired("SICK", date("2026-09-01"), date("2026-09-03"), List.of()));
    }
    @Test void fifthDayAllowedSixthNeedsProof() {
        var history = List.of(period("SICK", "2026-01-01", "2026-01-04"));
        assertFalse(LeavePolicy.proofRequired("SICK", date("2026-09-01"), date("2026-09-01"), history));
        assertTrue(LeavePolicy.proofRequired("SICK", date("2026-09-01"), date("2026-09-02"), history));
        assertFalse(LeavePolicy.proofRequired("CASUAL", date("2026-09-01"), date("2026-09-02"), history));
    }
    @Test void adjacentRequestsCountAcrossTypesAndOrder() {
        var history = List.of(period("SICK", "2026-09-03", "2026-09-03"), period("CASUAL", "2026-09-02", "2026-09-02"));
        assertTrue(LeavePolicy.proofRequired("SICK", date("2026-09-01"), date("2026-09-01"), history));
    }
    @Test void crossYearUsageIsSplit() {
        var history = List.of(period("SICK", "2026-12-28", "2027-01-02"));
        assertFalse(LeavePolicy.proofRequired("SICK", date("2027-09-01"), date("2027-09-02"), history));
    }
}
