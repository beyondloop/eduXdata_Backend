package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/syllabuses/documents")
public class SyllabusDocumentController {
    private final JdbcTemplate jdbc;
    public SyllabusDocumentController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private boolean role(Authentication auth,String role) {return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_"+role));}
    private String access(Authentication auth) {
        if(role(auth,"ADMIN"))return "";
        if(role(auth,"TEACHER"))return """
            and exists(select 1 from teacher_workloads w join teachers t on t.tenant_id=w.tenant_id and t.user_id=w.teacher_user_id and t.active
              where w.tenant_id=d.tenant_id and w.class_name=d.class_name and w.teacher_user_id=? and w.status='ACTIVE')
            """;
        return """
            and exists(select 1 from students st left join sections sec on sec.id=st.section_id left join classes c on c.id=sec.class_id
              where st.tenant_id=d.tenant_id and st.user_id=? and st.active
              and regexp_replace(lower(coalesce(st.class_name,c.name)), '^class\\s*', '')=regexp_replace(lower(d.class_name),'^class\\s*',''))
            """;
    }
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER','STUDENT')")
    public List<Map<String,Object>> list(Authentication auth) {
        Object[] args=role(auth,"ADMIN")?new Object[]{TenantContext.require()}:new Object[]{TenantContext.require(),auth.getName()};
        return jdbc.queryForList("select d.id,d.class_name,d.academic_year,d.period,d.file_name,d.updated_at from class_syllabus_documents d where d.tenant_id=? "+access(auth)+" order by d.academic_year desc,d.class_name,d.period",args);
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Object> upload(@RequestParam String className,@RequestParam String academicYear,@RequestParam String period,
            @RequestPart("file") MultipartFile file,Authentication auth) throws IOException {
        if(!academicYear.matches("20[0-9]{2}-[0-9]{2}") || Integer.parseInt(academicYear.substring(5))!=(Integer.parseInt(academicYear.substring(0,4))+1)%100
                || !Set.of("FULL_YEAR","TERM_1","TERM_2","FINAL").contains(period)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid academic year and term");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from classes where tenant_id=? and name=?) or exists(select 1 from teacher_workloads where tenant_id=? and class_name=? and status='ACTIVE')",Boolean.class,TenantContext.require(),className,TenantContext.require(),className)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an existing class");
        if(file.isEmpty() || file.getSize()>5*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a PDF up to 5 MB");
        byte[] bytes=file.getBytes();
        if(bytes.length<5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload a PDF document");
        String filename=Optional.ofNullable(file.getOriginalFilename()).orElse("syllabus.pdf").replaceAll("[^a-zA-Z0-9._ -]","_");
        if(filename.isBlank())filename="syllabus.pdf";
        jdbc.update("insert into class_syllabus_documents(id,tenant_id,class_name,academic_year,period,file_name,file_data,created_by) values(?,?,?,?,?,?,?,?) on conflict(tenant_id,class_name,academic_year,period) do update set file_name=excluded.file_name,file_data=excluded.file_data,created_by=excluded.created_by,updated_at=now()",
            UUID.randomUUID(),TenantContext.require(),className,academicYear,period,filename.substring(0,Math.min(180,filename.length())),bytes,auth.getName());
        return Map.of("saved",true);
    }
    @GetMapping("/{id}/file")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER','STUDENT')")
    public ResponseEntity<byte[]> download(@PathVariable UUID id,Authentication auth) {
        Object[] args=role(auth,"ADMIN")?new Object[]{TenantContext.require(),id}:new Object[]{TenantContext.require(),id,auth.getName()};
        var rows=jdbc.queryForList("select d.file_name,d.file_data from class_syllabus_documents d where d.tenant_id=? and d.id=? "+access(auth),args);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Syllabus PDF not found");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(rows.get(0).get("file_name").toString(),StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body((byte[])rows.get(0).get("file_data"));
    }
}
