package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeacherAdjustmentControllerTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final TeacherWorkloadController workloads = mock(TeacherWorkloadController.class);
    final TeacherAdjustmentController controller = new TeacherAdjustmentController(jdbc, workloads);
    final UUID tenant = UUID.randomUUID(), slot = UUID.randomUUID(), request = UUID.randomUUID();
    final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("TH001", "unused");
    @BeforeEach void setup() { TenantContext.set(tenant); when(jdbc.queryForObject(startsWith("select count(*) from teachers t where "),eq(Integer.class),any(Object[].class))).thenReturn(1); }
    @AfterEach void cleanup() { TenantContext.clear(); }
    @Test void availabilityCannotReadAnotherTeachersWorkload() {
        assertThrows(ResponseStatusException.class,()->controller.availableTeachers(slot,auth));
    }
    @Test void busyReplacementCannotReceiveRequest() {
        when(jdbc.queryForList(anyString(),eq(tenant),eq(slot),eq("TH001"))).thenReturn(List.of(Map.of("id",slot,"day_of_week","Monday","start_time","10:00","end_time","11:00")));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(tenant),eq("TH002"))).thenReturn(1);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(slot))).thenReturn(0);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(tenant),eq("TH002"),eq("Monday"),eq("11:00"),eq("10:00"))).thenReturn(1);
        var error=assertThrows(ResponseStatusException.class,()->controller.request(new TeacherAdjustmentController.Request(slot,"TH002","Cover class"),auth));
        assertEquals(409,error.getStatusCode().value());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void cannotRequestAnotherTeachersWorkload() {
        assertThrows(ResponseStatusException.class, () -> controller.request(new TeacherAdjustmentController.Request(slot, "TH002", "Cover class"), auth));
        verifyNoInteractions(workloads);
    }
    @Test void ineligibleSubjectOrClassTeacherIsBlockedBeforeRequestIsStored() {
        when(jdbc.queryForList(anyString(),eq(tenant),eq(slot),eq("TH001"))).thenReturn(List.of(Map.of("id",slot)));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(tenant),eq("TH002"))).thenReturn(1);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(slot))).thenReturn(0);
        when(jdbc.queryForObject(startsWith("select count(*) from teachers t where "),eq(Integer.class),any(Object[].class))).thenReturn(0);
        assertThrows(ResponseStatusException.class,()->controller.request(new TeacherAdjustmentController.Request(slot,"TH002","Cover class"),auth));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void submissionDoesNotChangeWorkload() {
        when(jdbc.queryForList(anyString(), eq(tenant), eq(slot), eq("TH001"))).thenReturn(List.of(Map.of("id", slot)));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(tenant), eq("TH002"))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(slot))).thenReturn(0);
        assertEquals("PENDING", controller.request(new TeacherAdjustmentController.Request(slot, "TH002", "Cover class"), auth).get("status"));
        verifyNoInteractions(workloads);
    }
    @Test void alreadyReviewedRequestCannotBeApproved() {
        assertThrows(ResponseStatusException.class, () -> controller.decide(request, new TeacherAdjustmentController.Decision(true), auth));
        verifyNoInteractions(workloads);
    }
    @Test void rejectionLeavesWorkloadUnchanged() {
        when(jdbc.queryForList(anyString(), eq(tenant), eq(request))).thenReturn(List.of(Map.of("workload_id", slot)));
        assertEquals("REJECTED", controller.decide(request, new TeacherAdjustmentController.Decision(false), auth).get("status"));
        verifyNoInteractions(workloads);
    }
    @Test void changedOwnerPreventsApproval() {
        when(jdbc.queryForList(anyString(), eq(tenant), eq(request))).thenReturn(List.of(Map.of("workload_id", slot, "requested_by", "TH001")));
        when(jdbc.queryForList(anyString(), eq(tenant), eq(slot))).thenReturn(List.of(Map.of("teacher_user_id", "TH003")));
        assertThrows(ResponseStatusException.class, () -> controller.decide(request, new TeacherAdjustmentController.Decision(true), auth));
        verifyNoInteractions(workloads);
    }
    @Test void approvalReassignsThroughWorkloadValidation() {
        when(jdbc.queryForList(anyString(), eq(tenant), eq(request))).thenReturn(List.of(Map.of("workload_id", slot, "requested_by", "TH001", "replacement_user_id", "TH002")));
        var workload = new HashMap<String,Object>();
        workload.put("id", slot); workload.put("teacher_user_id", "TH001"); workload.put("teacher_role", "Subject teacher");
        workload.put("subject", "Math"); workload.put("class_name", "10"); workload.put("section_name", "A"); workload.put("day_of_week", "Monday");
        workload.put("start_time", java.sql.Time.valueOf("10:00:00")); workload.put("end_time", java.sql.Time.valueOf("11:00:00")); workload.put("duration_minutes", 60);
        when(jdbc.queryForList(anyString(), eq(tenant), eq(slot))).thenReturn(List.of(workload));
        when(jdbc.queryForList(anyString(), eq(tenant), eq("TH002"))).thenReturn(List.of(Map.of("display_name", "Replacement")));
        assertEquals("APPROVED", controller.decide(request, new TeacherAdjustmentController.Decision(true), auth).get("status"));
        verify(workloads).update(eq(slot), argThat(w -> w.teacherUserId().equals("TH002") && w.subject().equals("Math")));
    }
}
