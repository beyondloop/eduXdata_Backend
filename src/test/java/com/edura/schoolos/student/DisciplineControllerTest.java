package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DisciplineControllerTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final StudentController students = mock(StudentController.class);
    final DisciplineController controller = new DisciplineController(jdbc, students);
    final UUID tenant = UUID.randomUUID(), student = UUID.randomUUID();
    final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("TH001", "unused");
    @BeforeEach void setup() { TenantContext.set(tenant); }
    @AfterEach void cleanup() { TenantContext.clear(); }
    @Test void thresholdIncludesExactlyOneHundredAndAbove() {
        when(students.list("", auth)).thenReturn(List.of(Map.of("id", student)));
        for (int total : new int[]{99, 100, 120}) {
            when(jdbc.queryForList(anyString(), eq(tenant), eq(student))).thenReturn(List.of(Map.of("points", total, "status", "APPROVED")));
            assertEquals(total >= 100, controller.list(auth).get(0).get("tc_eligible"));
        }
    }
    @Test void cannotRecordForUnassignedStudent() {
        when(students.list("", auth)).thenReturn(List.of());
        assertThrows(ResponseStatusException.class, () -> controller.add(new DisciplineController.Entry(student, "Mobile use", 20, LocalDate.now(), ""), auth));
        verifyNoInteractions(jdbc);
    }
    @Test void mobileUseAlwaysRecordsTwentyPoints() {
        when(students.list("", auth)).thenReturn(List.of(Map.of("id", student)));
        assertEquals(20, controller.add(new DisciplineController.Entry(student, "Mobile use", 1, LocalDate.now(), ""), auth).get("points"));
        verify(jdbc).update(anyString(), any(UUID.class), eq(tenant), eq(student), eq("Mobile use"), eq(20), any(LocalDate.class), eq(""), eq("TH001"));
    }
    @Test void eachListedIncidentUsesServerPointsEvenIfClientOverridesThem() {
        when(students.list("", auth)).thenReturn(List.of(Map.of("id", student)));
        for (var type : controller.types()) {
            assertEquals(type.points(), controller.add(new DisciplineController.Entry(student, type.incident(), 99, LocalDate.now(), ""), auth).get("points"));
        }
    }
    @Test void pointsCanBeOmittedAndIncidentNameIsNormalized() {
        when(students.list("", auth)).thenReturn(List.of(Map.of("id", student)));
        assertEquals(30, controller.add(new DisciplineController.Entry(student, " bullying ", null, LocalDate.now(), ""), auth).get("points"));
    }
    @Test void unknownIncidentIsRejected() {
        when(students.list("", auth)).thenReturn(List.of(Map.of("id", student)));
        assertThrows(ResponseStatusException.class, () -> controller.add(new DisciplineController.Entry(student, "Unknown incident", 10, LocalDate.now(), ""), auth));
        verifyNoInteractions(jdbc);
    }
    @Test void fiveMobileIncidentsReachTcThreshold() {
        when(students.list("", auth)).thenReturn(List.of(Map.of("id", student)));
        when(jdbc.queryForList(anyString(), eq(tenant), eq(student))).thenReturn(Collections.nCopies(5, Map.of("points", 20, "status", "APPROVED")));
        var record = controller.list(auth).get(0);
        assertEquals(100L, record.get("total_points"));
        assertEquals(true, record.get("tc_eligible"));
    }
    @Test void pendingAndRejectedPointsDoNotCountTowardTc() {
        when(students.list("", auth)).thenReturn(List.of(Map.of("id", student)));
        when(jdbc.queryForList(anyString(), eq(tenant), eq(student))).thenReturn(List.of(
            Map.of("points", 90, "status", "APPROVED"), Map.of("points", 20, "status", "PENDING"), Map.of("points", 30, "status", "REJECTED")));
        var record = controller.list(auth).get(0);
        assertEquals(90L, record.get("total_points"));
        assertEquals(false, record.get("tc_eligible"));
    }
    @Test void multipleCheckboxesSubmitFixedPointsForApproval() {
        when(students.list("", auth)).thenReturn(List.of(Map.of("id", student)));
        var results = controller.submit(new DisciplineController.Submission(student, List.of("Mobile use", "Bullying", " mobile use "), LocalDate.now(), ""), auth);
        assertEquals(2, results.size());
        assertEquals(50, results.stream().mapToInt(r -> (Integer)r.get("points")).sum());
        assertTrue(results.stream().allMatch(r -> r.get("status").equals("PENDING")));
    }
    @Test void invalidBatchIsRejectedBeforeAnyInsert() {
        assertThrows(ResponseStatusException.class, () -> controller.submit(new DisciplineController.Submission(student, List.of("Mobile use", "Unknown"), LocalDate.now(), ""), auth));
        verifyNoInteractions(jdbc);
    }
    @Test void decisionIsScopedToTenantAndOnlyPendingRecords() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(contains("and status = 'PENDING'"), eq("APPROVED"), eq("TH001"), eq(id), eq(tenant))).thenReturn(1);
        assertEquals("APPROVED", controller.decide(id, new DisciplineController.Decision(true), auth).get("status"));
    }
    @Test void repeatedOrOtherTenantDecisionCannotAddPoints() {
        assertThrows(ResponseStatusException.class, () -> controller.decide(UUID.randomUUID(), new DisciplineController.Decision(true), auth));
    }
}
