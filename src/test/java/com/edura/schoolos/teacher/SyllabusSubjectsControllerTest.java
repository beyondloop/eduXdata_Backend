package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyllabusSubjectsControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SyllabusSubjectsController controller = new SyllabusSubjectsController(jdbc);
    private final UUID tenant = UUID.randomUUID();
    @BeforeEach void setup() { TenantContext.set(tenant); }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void subjectChangesAreScopedToOneClassAndTenant() {
        controller.save(new SyllabusSubjectsController.Subjects(" 10 ", List.of("Math", "History")));
        verify(jdbc).update("delete from syllabus_class_subjects where tenant_id = ? and class_name = ?", tenant, "10");
        verify(jdbc).update("insert into syllabus_class_subjects(tenant_id, class_name, subject) values (?, ?, ?)", tenant, "10", "Math");
        verify(jdbc).update("insert into syllabus_class_subjects(tenant_id, class_name, subject) values (?, ?, ?)", tenant, "10", "History");
    }
    @Test void repeatedSubjectsIgnoreCaseAndWhitespace() {
        var result = controller.save(new SyllabusSubjectsController.Subjects("9", List.of(" Science ", "science", "SCIENCE")));
        assertEquals(List.of("Science"), new ArrayList<>((Collection<?>) result.get("subjects")));
        verify(jdbc, times(1)).update(contains("insert into syllabus_class_subjects"), eq(tenant), eq("9"), eq("Science"));
    }
    @Test void listUsesCurrentTenantOnly() {
        controller.list();
        verify(jdbc).queryForList(contains("where tenant_id = ?"), eq(tenant));
    }
}
