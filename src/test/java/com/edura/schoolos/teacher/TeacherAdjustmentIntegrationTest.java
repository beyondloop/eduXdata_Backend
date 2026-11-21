package com.edura.schoolos.teacher;
import com.edura.schoolos.security.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest @Transactional
@EnabledIfEnvironmentVariable(named="ADJUSTMENT_INTEGRATION_TESTS",matches="true")
class TeacherAdjustmentIntegrationTest {
 @Autowired JdbcTemplate jdbc; @Autowired TeacherAdjustmentController controller;
 UUID tenant=UUID.randomUUID(), slot=UUID.randomUUID();
 UsernamePasswordAuthenticationToken auth(String role,String user){var a=new UsernamePasswordAuthenticationToken(user,"",List.of(new SimpleGrantedAuthority("ROLE_"+role)));SecurityContextHolder.getContext().setAuthentication(a);return a;}
 @BeforeEach void setup(){
  TenantContext.set(tenant);jdbc.update("insert into tenants(id,name,slug) values(?, 'Adjustment test', ?)",tenant,"adjustment-test-"+tenant);
  for(String user:List.of("THADJ1","THADJ2"))jdbc.update("insert into teachers(id,tenant_id,user_id,teacher_id,email,password_hash,display_name) values(?,?,?,?,?,'unused',?)",UUID.randomUUID(),tenant,user,user,user+"@example.invalid",user);
  addSlot(slot,"THADJ1","Tuesday","10:00","11:00","10");
  addSlot(UUID.randomUUID(),"THADJ2","Monday","11:00","12:00","9");
 }
 void addSlot(UUID id,String teacher,String day,String start,String end,String className){jdbc.update("insert into teacher_workloads(id,tenant_id,teacher_user_id,teacher_name,teacher_role,subject,class_name,section_name,day_of_week,start_time,end_time,duration_minutes) values(?,?,?,?,'Subject teacher','Science',?,'A',?,?::time,?::time,60)",id,tenant,teacher,teacher,className,day,start,end);}
 @AfterEach void cleanup(){TenantContext.clear();SecurityContextHolder.clearContext();}
 UUID submit(){return (UUID)controller.request(new TeacherAdjustmentController.Request(slot,"THADJ2","Cover Science"),auth("TEACHER","THADJ1")).get("id");}
 @Test void availableTeacherRequestPersistsAndApprovalUpdatesClass(){
  assertEquals("THADJ2",controller.availableTeachers(slot,auth("TEACHER","THADJ1")).get(0).get("user_id"));
  UUID id=submit();assertEquals("Science",jdbc.queryForObject("select subject from teacher_adjustments where id=?",String.class,id));
  assertEquals("APPROVED",controller.decide(id,new TeacherAdjustmentController.Decision(true),auth("ADMIN","ADADJ")).get("status"));
  assertEquals("THADJ2",jdbc.queryForObject("select teacher_user_id from teacher_workloads where id=?",String.class,slot));
  assertEquals("ADADJ",jdbc.queryForObject("select reviewed_by from teacher_adjustments where id=?",String.class,id));
 }
 @Test void overlappingTeacherIsHiddenAndCannotSubmit(){
  addSlot(UUID.randomUUID(),"THADJ2","Tuesday","10:00","11:00","9");
  assertTrue(controller.availableTeachers(slot,auth("TEACHER","THADJ1")).isEmpty());
  assertThrows(ResponseStatusException.class,this::submit);
  assertEquals(0,jdbc.queryForObject("select count(*) from teacher_adjustments where tenant_id=?",Integer.class,tenant));
 }
 @Test void classTeacherConflictIsHiddenBeforeSubmission(){
  jdbc.update("update teacher_workloads set teacher_role='Class teacher' where tenant_id=?",tenant);
  assertTrue(controller.availableTeachers(slot,auth("TEACHER","THADJ1")).isEmpty());
  assertThrows(ResponseStatusException.class,this::submit);
 }
 @Test void changedSchedulePreventsApprovalAndHistorySurvivesWorkloadDeletion(){
  UUID id=submit();jdbc.update("update teacher_workloads set day_of_week='Wednesday' where id=?",slot);
  assertThrows(ResponseStatusException.class,()->controller.decide(id,new TeacherAdjustmentController.Decision(true),auth("ADMIN","ADADJ")));
  jdbc.update("delete from teacher_workloads where id=?",slot);
  var rows=controller.list(auth("ADMIN","ADADJ"));assertEquals(1,rows.size());assertEquals("Tuesday",rows.get(0).get("day_of_week"));
 }
}
