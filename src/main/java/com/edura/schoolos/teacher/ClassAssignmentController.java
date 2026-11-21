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
@RequestMapping("/api/v1/class-assignments")
public class ClassAssignmentController {
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final String STUDENT_DIRECTORY = """
        (select st.*, coalesce(st.class_name,c.name) as resolved_class_name,
                coalesce(st.section_name,sec.name) as resolved_section_name
         from students st
         left join sections sec on sec.tenant_id=st.tenant_id and sec.id=st.section_id
         left join classes c on c.tenant_id=sec.tenant_id and c.id=sec.class_id)
        """;
    private final JdbcTemplate jdbc;
    public ClassAssignmentController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private boolean teacher(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a -> "ROLE_TEACHER".equals(a.getAuthority())); }

    @GetMapping("/classes")
    @PreAuthorize("hasRole('TEACHER')")
    public List<Map<String,Object>> classes(Authentication auth) {
        return jdbc.queryForList("""
            select distinct regexp_replace(w.class_name, '^class\\s*', '', 'i') as class_name,
                   w.section_name, w.subject
            from teacher_workloads w
            where w.tenant_id=? and w.teacher_user_id=? and w.status='ACTIVE'
            order by class_name, w.section_name, w.subject
            """, TenantContext.require(), auth.getName());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER','STUDENT')")
    public List<Map<String,Object>> list(Authentication auth) {
        var tenant = TenantContext.require();
        if (teacher(auth)) return jdbc.queryForList("""
            select a.id, a.ticket_number, a.class_name, a.section_name, a.subject, a.title, a.instructions,
                   a.file_name, a.content_type, a.created_at, p.student_id,
                   coalesce(p.student_name,s.display_name) as student_name, coalesce(p.roll_number,s.roll_number) as roll_number,
                   coalesce(p.status,'TODO') as status, p.feedback, p.reviewed_at,
                   coalesce(p.assigned_at,a.created_at) as assigned_at, p.completed_at
            from class_assignments a
            left join %s s on s.tenant_id=a.tenant_id and s.active
              and regexp_replace(lower(trim(coalesce(s.resolved_class_name,''))), '^class\\s*', '')=regexp_replace(lower(trim(a.class_name)), '^class\\s*', '')
              and lower(trim(coalesce(s.resolved_section_name,'')))=lower(trim(a.section_name))
            left join class_assignment_progress p on p.assignment_id=a.id and p.student_id=s.id
            where a.tenant_id=? and a.teacher_user_id=?
            order by a.created_at desc, s.display_name
            """.formatted(STUDENT_DIRECTORY), tenant, auth.getName());
        var rows = jdbc.queryForList("""
            select a.id, a.ticket_number, a.class_name, a.section_name, a.subject, a.title, a.instructions,
                   a.file_name, a.content_type, a.teacher_name, a.created_at,
                   coalesce(p.student_name,s.display_name) as student_name, coalesce(p.roll_number,s.roll_number) as roll_number,
                   coalesce(p.status,'TODO') as status, p.feedback, p.reviewed_at,
                   coalesce(p.assigned_at,a.created_at) as assigned_at, p.completed_at
            from class_assignments a
            join %s s on s.tenant_id=a.tenant_id and s.user_id=? and s.active
              and regexp_replace(lower(trim(coalesce(s.resolved_class_name,''))), '^class\\s*', '')=regexp_replace(lower(trim(a.class_name)), '^class\\s*', '')
              and lower(trim(coalesce(s.resolved_section_name,'')))=lower(trim(a.section_name))
            left join class_assignment_progress p on p.assignment_id=a.id and p.student_id=s.id
            where a.tenant_id=? order by a.created_at desc
            """.formatted(STUDENT_DIRECTORY), auth.getName(), tenant);
        jdbc.update("""
            insert into class_assignment_progress(assignment_id,student_id,student_name,roll_number,assigned_at)
            select a.id,s.id,s.display_name,s.roll_number,a.created_at from class_assignments a join %s s on s.tenant_id=a.tenant_id and s.user_id=? and s.active
              and regexp_replace(lower(trim(coalesce(s.resolved_class_name,''))), '^class\\s*', '')=regexp_replace(lower(trim(a.class_name)), '^class\\s*', '')
              and lower(trim(coalesce(s.resolved_section_name,'')))=lower(trim(a.section_name))
            where a.tenant_id=? on conflict do nothing
            """.formatted(STUDENT_DIRECTORY), auth.getName(), tenant);
        return rows;
    }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@RequestParam String className, @RequestParam String sectionName,
            @RequestParam String subject, @RequestParam String title, @RequestParam(defaultValue="") String instructions,
            @RequestPart(value="file", required=false) MultipartFile file, Authentication auth) throws IOException {
        var tenant = TenantContext.require();
        className=className.trim(); sectionName=sectionName.trim(); subject=subject.trim(); title=title.trim();
        if (title.isBlank() || title.length()>200 || subject.isBlank() || subject.length()>120 || instructions.length()>10000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Add a title and subject; keep the title under 200 characters and instructions under 10,000 characters.");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from teacher_workloads where tenant_id=? and teacher_user_id=? and status='ACTIVE' and regexp_replace(lower(trim(class_name)), '^class\\s*', '')=regexp_replace(lower(trim(?)), '^class\\s*', '') and lower(trim(section_name))=lower(trim(?)) and lower(trim(subject))=lower(trim(?)))", Boolean.class, tenant, auth.getName(), className, sectionName, subject)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You can only assign tasks for subjects and class sections in your active workload.");
        String fileName=null, contentType=null; byte[] data=null;
        if (file!=null && !file.isEmpty()) {
            if (file.getSize()>MAX_FILE_SIZE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Attachments must be 10 MB or smaller.");
            contentType=Optional.ofNullable(file.getContentType()).orElse("application/octet-stream");
            if (!(contentType.equals("application/pdf") || contentType.startsWith("image/"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload an image or PDF file.");
            data=file.getBytes();
            if (contentType.equals("application/pdf") && (data.length<5 || !new String(data,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The uploaded file is not a valid PDF.");
            fileName=Optional.ofNullable(file.getOriginalFilename()).orElse("assignment-file").replaceAll("[^a-zA-Z0-9._ -]","_");
            fileName=fileName.substring(0,Math.min(180,fileName.length()));
        }
        UUID id=UUID.randomUUID();
        jdbc.update("insert into class_assignments(id,tenant_id,teacher_user_id,teacher_name,class_name,section_name,subject,title,instructions,file_name,content_type,file_data) values(?,?,?,?,?,?,?,?,?,?,?,?)",
            id,tenant,auth.getName(),jdbc.queryForObject("select display_name from teachers where tenant_id=? and user_id=?",String.class,tenant,auth.getName()),className,sectionName,subject,title,instructions.trim(),fileName,contentType,data);
        jdbc.update("""
            insert into class_assignment_progress(assignment_id,student_id,student_name,roll_number,assigned_at)
            select a.id,s.id,s.display_name,s.roll_number,a.created_at from class_assignments a join %s s on s.tenant_id=a.tenant_id and s.active
              and regexp_replace(lower(trim(coalesce(s.resolved_class_name,''))), '^class\\s*', '')=regexp_replace(lower(trim(?)), '^class\\s*', '')
              and lower(trim(coalesce(s.resolved_section_name,'')))=lower(trim(?)) where a.id=? on conflict do nothing
            """.formatted(STUDENT_DIRECTORY),className,sectionName,id);
        long ticketNumber=jdbc.queryForObject("select ticket_number from class_assignments where id=?",Long.class,id);
        return Map.of("id",id,"ticketNumber",ticketNumber,"className",className,"sectionName",sectionName,"subject",subject,"title",title,"instructions",instructions.trim(),"status","TODO");
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('STUDENT')")
    public Map<String,Object> updateStatus(@PathVariable UUID id, @RequestBody StatusChange request, Authentication auth) {
        if (!Set.of("IN_PROGRESS","DONE").contains(request.status())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose In progress or Done.");
        var tenant=TenantContext.require();
        var students=jdbc.queryForList("select id from students where tenant_id=? and user_id=? and active",tenant,auth.getName());
        if(students.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student account not found.");
        UUID studentId=(UUID)students.get(0).get("id");
        jdbc.update("""
            insert into class_assignment_progress(assignment_id,student_id,student_name,roll_number,assigned_at)
            select a.id,s.id,s.display_name,s.roll_number,a.created_at from class_assignments a join %s s on s.tenant_id=a.tenant_id and s.id=? and s.active
              and regexp_replace(lower(trim(coalesce(s.resolved_class_name,''))), '^class\\s*', '')=regexp_replace(lower(trim(a.class_name)), '^class\\s*', '')
              and lower(trim(coalesce(s.resolved_section_name,'')))=lower(trim(a.section_name))
            where a.tenant_id=? and a.id=? on conflict do nothing
            """.formatted(STUDENT_DIRECTORY),studentId,tenant,id);
        var current=jdbc.queryForList("select p.status from class_assignment_progress p join class_assignments a on a.id=p.assignment_id where a.tenant_id=? and a.id=? and p.student_id=?",tenant,id,studentId);
        if(current.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Task not found for your class.");
        String old=current.get(0).get("status").toString();
        if("DONE".equals(old) || "COMPLETED".equals(old)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Tasks marked done are locked while awaiting teacher review.");
        if("TODO".equals(old) && "DONE".equals(request.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Start the task before marking it done.");
        if("IN_PROGRESS".equals(old) && "IN_PROGRESS".equals(request.status())) return Map.of("status",old);
        jdbc.update("update class_assignment_progress set status=?, updated_at=now() where assignment_id=? and student_id=?",request.status(),id,studentId);
        return Map.of("status",request.status());
    }

    @PostMapping("/{id}/students/{studentId}/teacher-status")
    @PreAuthorize("hasRole('TEACHER')")
    public Map<String,Object> teacherStatus(@PathVariable UUID id,@PathVariable UUID studentId,@RequestBody StatusChange request,Authentication auth) {
        String next=request.status();
        if(!"COMPLETED".equals(next)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Teachers can move student tickets from Review / checking to Completed only.");
        int changed=jdbc.update("""
            update class_assignment_progress p set status='COMPLETED', reviewed_at=now(), completed_at=now(), updated_at=now()
            from class_assignments a where p.assignment_id=a.id and a.tenant_id=? and a.teacher_user_id=? and a.id=? and p.student_id=?
              and p.status='DONE'
            """,TenantContext.require(),auth.getName(),id,studentId);
        if(changed==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"That student task cannot move to the selected column.");
        return Map.of("status",next);
    }

    @PostMapping("/{id}/students/{studentId}/feedback")
    @PreAuthorize("hasRole('TEACHER')")
    public Map<String,Object> feedback(@PathVariable UUID id,@PathVariable UUID studentId,@RequestBody Feedback request,Authentication auth) {
        String feedback=Optional.ofNullable(request.feedback()).orElse("").trim();
        if(feedback.isBlank() || feedback.length()>4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Feedback is required and must be under 4,000 characters.");
        int updated=jdbc.update("""
            update class_assignment_progress p set feedback=?,reviewed_at=now(),updated_at=now()
            from class_assignments a where p.assignment_id=a.id and a.tenant_id=? and a.teacher_user_id=? and a.id=? and p.student_id=? and p.status in ('DONE','COMPLETED')
            """,feedback,TenantContext.require(),auth.getName(),id,studentId);
        if(updated==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Feedback can be added only to a completed task in one of your classes.");
        return Map.of("feedback",feedback);
    }

    @PostMapping("/{id}/students/{studentId}/review")
    @PreAuthorize("hasRole('TEACHER')")
    public Map<String,Object> review(@PathVariable UUID id,@PathVariable UUID studentId,@RequestBody Review request,Authentication auth) {
        String feedback=Optional.ofNullable(request.feedback()).orElse("").trim();
        if(feedback.length()>4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Feedback must be under 4,000 characters.");
        int updated=jdbc.update("""
            update class_assignment_progress p set status='COMPLETED', feedback=nullif(?,''), reviewed_at=now(), completed_at=now(), updated_at=now()
            from class_assignments a where p.assignment_id=a.id and a.tenant_id=? and a.teacher_user_id=? and a.id=? and p.student_id=? and p.status='DONE'
            """,feedback,TenantContext.require(),auth.getName(),id,studentId);
        if(updated==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only a done task assigned to a student in your class can be reviewed.");
        return Map.of("status","COMPLETED","feedback",feedback);
    }

    @GetMapping("/{id}/comments")
    @PreAuthorize("hasAnyRole('TEACHER','STUDENT')")
    public List<Map<String,Object>> comments(@PathVariable UUID id,@RequestParam(required=false) UUID studentId,Authentication auth) {
        requireTaskAccess(id,auth);
        UUID target=teacher(auth)?studentId:ownStudentId(auth);
        if(teacher(auth) && target!=null) requireStudentAssignment(id,target,auth);
        if(target==null) return jdbc.queryForList("select id,author_name,author_role,body,created_at from class_assignment_comments where tenant_id=? and assignment_id=? and student_id is null order by created_at,id",TenantContext.require(),id);
        return jdbc.queryForList("select id,author_name,author_role,body,created_at from class_assignment_comments where tenant_id=? and assignment_id=? and (student_id is null or student_id=?) order by created_at,id",TenantContext.require(),id,target);
    }

    @PostMapping("/{id}/comments")
    @PreAuthorize("hasAnyRole('TEACHER','STUDENT')")
    public Map<String,Object> addComment(@PathVariable UUID id,@RequestBody Comment request,Authentication auth) {
        requireTaskAccess(id,auth);
        String body=Optional.ofNullable(request.body()).orElse("").trim();
        if(body.isBlank() || body.length()>4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Comments must contain 1 to 4,000 characters.");
        String role=teacher(auth)?"TEACHER":"STUDENT";
        UUID target=teacher(auth)?request.studentId():ownStudentId(auth);
        if(teacher(auth) && target!=null) requireStudentAssignment(id,target,auth);
        String name=teacher(auth)
            ? jdbc.queryForObject("select display_name from teachers where tenant_id=? and user_id=?",String.class,TenantContext.require(),auth.getName())
            : jdbc.queryForObject("select display_name from students where tenant_id=? and user_id=? and active",String.class,TenantContext.require(),auth.getName());
        UUID commentId=UUID.randomUUID();
        jdbc.update("insert into class_assignment_comments(id,tenant_id,assignment_id,student_id,author_user_id,author_name,author_role,body) values(?,?,?,?,?,?,?,?)",commentId,TenantContext.require(),id,target,auth.getName(),name,role,body);
        return Map.of("id",commentId,"authorName",name,"authorRole",role,"body",body);
    }

    private void requireTaskAccess(UUID id,Authentication auth) {
        String predicate=teacher(auth)?"a.teacher_user_id=?":"exists(select 1 from "+STUDENT_DIRECTORY+" s where s.tenant_id=a.tenant_id and s.user_id=? and s.active and regexp_replace(lower(trim(coalesce(s.resolved_class_name,''))), '^class\\s*', '')=regexp_replace(lower(trim(a.class_name)), '^class\\s*', '') and lower(trim(coalesce(s.resolved_section_name,'')))=lower(trim(a.section_name)))";
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from class_assignments a where a.tenant_id=? and a.id=? and "+predicate+")",Boolean.class,TenantContext.require(),id,auth.getName())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Assignment not found for your class.");
    }

    private UUID ownStudentId(Authentication auth) {
        var rows=jdbc.queryForList("select id from students where tenant_id=? and user_id=? and active",TenantContext.require(),auth.getName());
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student account not found.");
        return (UUID)rows.get(0).get("id");
    }

    private void requireStudentAssignment(UUID assignmentId,UUID studentId,Authentication auth) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from class_assignments a join "+STUDENT_DIRECTORY+" s on s.tenant_id=a.tenant_id and s.id=? and s.active where a.tenant_id=? and a.id=? and a.teacher_user_id=? and regexp_replace(lower(trim(coalesce(s.resolved_class_name,''))), '^class\\s*', '')=regexp_replace(lower(trim(a.class_name)), '^class\\s*', '') and lower(trim(coalesce(s.resolved_section_name,'')))=lower(trim(a.section_name)))",Boolean.class,studentId,TenantContext.require(),assignmentId,auth.getName())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student is not assigned to this class task.");
    }

    @GetMapping("/{id}/file")
    @PreAuthorize("hasAnyRole('TEACHER','STUDENT')")
    public ResponseEntity<byte[]> file(@PathVariable UUID id,Authentication auth) {
        var tenant=TenantContext.require();
        String predicate=teacher(auth)?"a.teacher_user_id=?":"exists(select 1 from "+STUDENT_DIRECTORY+" s where s.tenant_id=a.tenant_id and s.user_id=? and s.active and regexp_replace(lower(trim(coalesce(s.resolved_class_name,''))), '^class\\s*', '')=regexp_replace(lower(trim(a.class_name)), '^class\\s*', '') and lower(trim(coalesce(s.resolved_section_name,'')))=lower(trim(a.section_name)))";
        var files=jdbc.queryForList("select a.file_name,a.content_type,a.file_data from class_assignments a where a.tenant_id=? and a.id=? and a.file_data is not null and "+predicate,tenant,id,auth.getName());
        if(files.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Attachment not found.");
        var file=files.get(0); MediaType type=MediaType.parseMediaType(file.get("content_type").toString());
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.get("file_name").toString(),StandardCharsets.UTF_8).build().toString()).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body((byte[])file.get("file_data"));
    }
    public record StatusChange(String status) {}
    public record Feedback(String feedback) {}
    public record Review(String feedback) {}
    public record Comment(String body,UUID studentId) {}
}
