package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClassSectionControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ClassSectionController controller = new ClassSectionController(jdbc);
    private final UUID tenant = UUID.randomUUID();
    @BeforeEach void setup() { TenantContext.set(tenant); }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void duplicateClassIsRejected() {
        when(jdbc.queryForObject(contains("lower(trim(name))"), eq(Boolean.class), eq(tenant), eq("10"))).thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> controller.createClass(new ClassSectionController.ClassRequest(" 10 ")));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void sectionCannotBeCreatedForAnotherTenantsClass() {
        UUID classId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("id = ? and tenant_id = ? and active"), eq(Boolean.class), eq(classId), eq(tenant))).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> controller.createSection(new ClassSectionController.SectionRequest(classId, "A")));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void sectionIsSavedWithSelectedClassAndTenant() {
        UUID classId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("id = ? and tenant_id = ? and active"), eq(Boolean.class), eq(classId), eq(tenant))).thenReturn(true);
        var result = controller.createSection(new ClassSectionController.SectionRequest(classId, " A "));
        verify(jdbc).update(contains("insert into sections"), eq(result.get("id")), eq(tenant), eq(classId), eq("A"));
    }
}
