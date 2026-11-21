package com.edura.schoolos.teacher;
import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class SyllabusTopicDeletionTest {
    JdbcTemplate jdbc; SyllabusController controller; UUID tenant; UUID id;
    final UsernamePasswordAuthenticationToken admin=new UsernamePasswordAuthenticationToken("ADMIN1","",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @BeforeEach void setup(){jdbc=mock(JdbcTemplate.class);controller=new SyllabusController(jdbc);tenant=UUID.randomUUID();id=UUID.randomUUID();TenantContext.set(tenant);}
    @AfterEach void cleanup(){TenantContext.clear();}
    @Test void deletionReleasesImportKeySoTopicCanBeImportedAgain(){
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("id",id)),List.of(Map.of("import_key","topic-key")));
        assertEquals(true,controller.deleteTopic(id,admin).get("deleted"));
        verify(jdbc).update("delete from syllabus_topics where id=?",id);
        verify(jdbc).update("delete from syllabus_import_keys where tenant_id=? and import_key=?",tenant,"topic-key");
    }
    @Test void topicOutsideTenantCannotBeDeleted(){
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        assertThrows(ResponseStatusException.class,()->controller.deleteTopic(id,admin));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
