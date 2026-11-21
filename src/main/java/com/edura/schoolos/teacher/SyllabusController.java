package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/syllabuses")
public class SyllabusController {
    private final JdbcTemplate jdbc;
    public SyllabusController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Access follows current teaching assignments, including approved workload transfers.
    private static final String TEACHER_ACCESS = """
        and exists (select 1 from teacher_workloads w
          join teachers teacher on teacher.tenant_id = w.tenant_id and teacher.user_id = w.teacher_user_id and teacher.active
          where w.tenant_id = s.tenant_id and w.class_name = s.class_name and w.section_name = s.section_name
            and lower(w.subject) = lower(s.subject) and w.status = 'ACTIVE' and w.teacher_user_id = ? and (s.assigned_teacher is null or s.assigned_teacher = w.teacher_user_id))
        """;

    private boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
    private Object[] scope(Authentication auth) {
        return admin(auth) ? new Object[]{TenantContext.require()} : new Object[]{TenantContext.require(), auth.getName()};
    }
    private boolean student(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_STUDENT")); }
    private String access(Authentication auth) { return admin(auth) ? "" : student(auth) ? """
        and exists (select 1 from students st left join sections sec on sec.id = st.section_id
        left join classes c on c.id = sec.class_id where st.tenant_id = s.tenant_id and st.user_id = ? and st.active
        and regexp_replace(lower(coalesce(st.class_name, c.name)), '^class\\s*', '') = lower(s.class_name)
        and lower(coalesce(st.section_name, sec.name)) = lower(s.section_name))
        """ : TEACHER_ACCESS; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER','STUDENT')")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Authentication auth) {
        String filter = " where s.tenant_id = ? " + access(auth);
        var plans = jdbc.queryForList("""
            select s.id, s.title, s.class_name, s.section_name, s.subject, s.file_name, s.created_at, s.year_start, s.period, s.academic_year, s.subject_code, s.assigned_teacher,
              (select string_agg(distinct teacher.display_name, ', ' order by teacher.display_name)
               from teacher_workloads w join teachers teacher on teacher.tenant_id = w.tenant_id
                 and teacher.user_id = w.teacher_user_id and teacher.active
               where w.tenant_id = s.tenant_id and w.class_name = s.class_name and w.section_name = s.section_name
                 and lower(w.subject) = lower(s.subject) and w.status = 'ACTIVE' and (s.assigned_teacher is null or s.assigned_teacher = w.teacher_user_id)) as teacher_names
            from syllabuses s
            """ + filter + " order by s.created_at desc, s.id", scope(auth));
        var topics = jdbc.queryForList("""
            select t.id, t.syllabus_id, t.title, t.deadline, t.deadline_time, t.completed_at, t.completed_by, t.chapter, t.description, t.start_date, t.priority, t.points, t.days_late, t.completion_status,
              case when t.completed_at is not null then 'DONE' else coalesce(t.workflow_status,'TODO') end as workflow_status,
              coalesce(teacher.display_name, t.completed_by) as completed_by_name,
              case when t.completed_at is not null then
                case when (t.completed_at at time zone 'Asia/Kolkata') > t.deadline + t.deadline_time then 'COMPLETED_LATE' else 'COMPLETED' end
                when t.deadline + t.deadline_time < (now() at time zone 'Asia/Kolkata') then 'OVERDUE'
                when t.workflow_status='IN_PROGRESS' then 'IN_PROGRESS'
                when t.workflow_status='TODO' then 'PENDING'
                when exists (select 1 from syllabus_notes n where n.topic_id = t.id) then 'IN_PROGRESS'
                else 'PENDING' end as status
            from syllabus_topics t join syllabuses s on s.id = t.syllabus_id
            left join teachers teacher on teacher.tenant_id = s.tenant_id and teacher.user_id = t.completed_by
            """ + filter + " order by t.position, t.id", scope(auth));
        var notes = jdbc.queryForList("""
            select n.id, n.topic_id, n.body, n.created_at, n.author_id,
              coalesce(teacher.display_name, n.author_id) as author_name
            from syllabus_notes n join syllabus_topics t on t.id = n.topic_id join syllabuses s on s.id = t.syllabus_id
            left join teachers teacher on teacher.tenant_id = s.tenant_id and teacher.user_id = n.author_id
            """ + filter + " order by n.created_at desc, n.id", scope(auth));
        var notesByTopic = notes.stream().collect(Collectors.groupingBy(n -> n.get("topic_id")));
        topics.forEach(t -> t.put("notes", notesByTopic.getOrDefault(t.get("id"), List.of())));
        var topicsByPlan = topics.stream().collect(Collectors.groupingBy(t -> t.get("syllabus_id")));
        plans.forEach(s -> s.put("topics", topicsByPlan.getOrDefault(s.get("id"), List.of())));
        if (student(auth)) topics.forEach(t -> { t.put("notes", List.of()); t.remove("completed_by"); t.remove("completed_by_name"); t.remove("points"); t.remove("days_late"); t.remove("completion_status"); });
        if (!admin(auth)) topics.forEach(t -> t.remove("points"));
        return plans;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@Valid @RequestPart("syllabus") CreateSyllabus entry,
            @RequestPart(value = "file", required = false) MultipartFile file, Authentication auth) {
        return save(entry, file, auth, null, "FULL_YEAR");
    }

    @PostMapping(value = "/annual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createAnnual(@Valid @RequestPart("syllabus") AnnualSyllabus entry,
            @RequestPart(value = "file", required = false) MultipartFile file, Authentication auth) {
        if (entry.yearStart().getDayOfMonth() != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Academic year must start on the first day of a month");
        var subjects = new HashSet<String>();
        for (SubjectTargets subject : entry.subjects()) {
            if (!subjects.add(subject.subject().trim().toLowerCase(Locale.ROOT)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each subject may appear only once in the annual plan");
            for (TopicInput topic : subject.topics()) validatePeriod(topic.deadline(), entry.yearStart(), entry.period());
        }
        var ids = new ArrayList<Object>();
        for (SubjectTargets subject : entry.subjects()) {
            UUID id=(UUID) save(new CreateSyllabus(entry.title(), entry.className(), entry.sectionName(), subject.subject(), subject.topics()), file, auth, entry.yearStart(), entry.period()).get("id");
            if(subject.teacherUserId()!=null) assignTeacher(id,new TeacherAssignment(subject.teacherUserId()));
            ids.add(id);
        }
        return Map.of("ids", ids, "subjects", ids.size());
    }

    private void validateYear(LocalDate deadline, LocalDate yearStart) {
        if (yearStart != null && (deadline.isBefore(yearStart) || !deadline.isBefore(yearStart.plusYears(1))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every topic deadline must be within the selected academic year");
    }

    private void validatePeriod(LocalDate deadline, LocalDate yearStart, String period) {
        validateYear(deadline, yearStart);
        if (yearStart != null && (("TERM_1".equals(period) && !deadline.isBefore(yearStart.plusMonths(6)))
                || ("TERM_2".equals(period) && deadline.isBefore(yearStart.plusMonths(6)))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Topic deadline must fall within the selected term");
    }

    @PostMapping(value = "/extract-subjects", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public SyllabusPdfExtractor.Result extractSubjects(@RequestPart("file") MultipartFile file,
            @RequestParam String className, @RequestParam String sectionName) {
        var subjects = jdbc.queryForList("""
            select distinct w.subject from teacher_workloads w join teachers t
              on t.tenant_id = w.tenant_id and t.user_id = w.teacher_user_id and t.active
            where w.tenant_id = ? and w.class_name = ? and w.section_name = ? and w.status = 'ACTIVE'
            order by w.subject
            """, String.class, TenantContext.require(), className, sectionName);
        if (subjects.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a class with active subject assignments first");
        return SyllabusPdfExtractor.extract(file, subjects);
    }

    private Map<String, Object> save(CreateSyllabus entry, MultipartFile file, Authentication auth, LocalDate yearStart, String period) {
        UUID tenant = TenantContext.require();
        Integer assignments = jdbc.queryForObject("""
            select count(*) from teacher_workloads w join teachers t on t.tenant_id = w.tenant_id and t.user_id = w.teacher_user_id
            where w.tenant_id = ? and w.class_name = ? and w.section_name = ? and lower(w.subject) = lower(?)
              and w.status = 'ACTIVE' and t.active
            """, Integer.class, tenant, entry.className(), entry.sectionName(), entry.subject());
        if (assignments == null || assignments == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assign a teacher to this class, section and subject in Teacher Workload first");
        byte[] data = null;
        String filename = null;
        if (file != null && !file.isEmpty()) {
            if (file.getSize() > 5 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Syllabus PDF must be 5 MB or smaller");
            try { data = file.getBytes(); } catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read syllabus PDF"); }
            if (data.length < 5 || !new String(data, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a PDF document");
            filename = Optional.ofNullable(file.getOriginalFilename()).orElse("syllabus.pdf").replaceAll("[^a-zA-Z0-9._ -]", "_");
            if (filename.isBlank()) filename = "syllabus.pdf";
            filename = filename.substring(0, Math.min(filename.length(), 180));
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into syllabuses(id, tenant_id, title, class_name, section_name, subject, file_name, file_data, created_by, year_start, period) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, tenant, entry.title().trim(), entry.className(), entry.sectionName(), entry.subject(), filename, data, auth.getName(), yearStart, period);
        int position = 0;
        for (TopicInput topic : entry.topics()) {
            jdbc.update("insert into syllabus_topics(id, syllabus_id, title, deadline, deadline_time, position) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, topic.title().trim(), topic.deadline(), topic.deadlineTime(), position++);
        }
        return Map.of("id", id);
    }

    @GetMapping("/{id}/file")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER','STUDENT')")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, Authentication auth) {
        Object[] params = admin(auth) ? new Object[]{TenantContext.require(), id} : new Object[]{TenantContext.require(), id, auth.getName()};
        var rows = jdbc.queryForList("select s.file_name, s.file_data from syllabuses s where s.tenant_id = ? and s.id = ? and s.file_data is not null " + access(auth), params);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Syllabus document not found");
        var row = rows.get(0);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(row.get("file_name").toString(), StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body((byte[]) row.get("file_data"));
    }

    private Map<String, Object> requireTopic(UUID id, Authentication auth) {
        Object[] params = admin(auth) ? new Object[]{TenantContext.require(), id} : new Object[]{TenantContext.require(), id, auth.getName()};
        var rows = jdbc.queryForList("""
            select t.id, t.completed_at, t.start_date, s.year_start, s.period from syllabus_topics t join syllabuses s on s.id = t.syllabus_id
            where s.tenant_id = ? and t.id = ?
            """ + access(auth) + " for update of t", params);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Topic not found in your assigned syllabus");
        return rows.get(0);
    }

    @PutMapping("/{id}/teacher")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String,Object> assignTeacher(@PathVariable UUID id, @Valid @RequestBody TeacherAssignment entry) {
        var rows = jdbc.queryForList("select * from syllabuses where tenant_id=? and id=? for update", TenantContext.require(), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Syllabus not found");
        var plan = rows.get(0);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from teacher_workloads w join teachers t on t.tenant_id=w.tenant_id and t.user_id=w.teacher_user_id and t.active
            where w.tenant_id=? and w.teacher_user_id=? and w.class_name=? and w.section_name=? and lower(w.subject)=lower(?) and w.status='ACTIVE')
            """, Boolean.class,TenantContext.require(),entry.teacherUserId(),plan.get("class_name"),plan.get("section_name"),plan.get("subject"))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Teacher must have an active workload for this class, section and subject");
        jdbc.update("update syllabuses set assigned_teacher=? where id=?",entry.teacherUserId(),id);
        return Map.of("id",id);
    }

    @PostMapping("/{id}/topics")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String,Object> addTopic(@PathVariable UUID id, @Valid @RequestBody TopicInput entry) {
        var rows=jdbc.queryForList("select year_start,period from syllabuses where tenant_id=? and id=? for update",TenantContext.require(),id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Syllabus not found");
        var plan=rows.get(0);
        if(plan.get("year_start")!=null) validatePeriod(entry.deadline(),LocalDate.parse(plan.get("year_start").toString()),plan.get("period").toString());
        UUID topic=UUID.randomUUID();
        jdbc.update("insert into syllabus_topics(id,syllabus_id,title,deadline,deadline_time,position) values(?,?,?,?,?,(select coalesce(max(position),-1)+1 from syllabus_topics where syllabus_id=?))",topic,id,entry.title().trim(),entry.deadline(),entry.deadlineTime(),id);
        return Map.of("id",topic);
    }

    @PutMapping(value="/{id}/file",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String,Object> uploadFile(@PathVariable UUID id,@RequestPart("file") MultipartFile file) throws IOException {
        byte[] bytes=file.getBytes();
        if(bytes.length<5 || bytes.length>5*1024*1024 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a PDF up to 5 MB");
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("syllabus.pdf").replaceAll("[^a-zA-Z0-9._ -]","_");
        if(name.isBlank()) name="syllabus.pdf";
        if(jdbc.update("update syllabuses set file_name=?,file_data=? where tenant_id=? and id=?",name.substring(0,Math.min(180,name.length())),bytes,TenantContext.require(),id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Syllabus not found");
        return Map.of("id",id);
    }
    public record TeacherAssignment(@NotBlank @Size(max=20) String teacherUserId) {}

    @PutMapping("/topics/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String, Object> updateTopic(@PathVariable UUID id, @Valid @RequestBody TopicInput entry, Authentication auth) {
        var topic = requireTopic(id, auth);
        if (topic.get("completed_at") != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Completed topics cannot be changed");
        if (topic.get("year_start") != null) validatePeriod(entry.deadline(), LocalDate.parse(topic.get("year_start").toString()), topic.get("period").toString());
        if (topic.get("start_date") != null && entry.deadline().isBefore(LocalDate.parse(topic.get("start_date").toString())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Deadline cannot precede start date");
        jdbc.update("update syllabus_topics set title = ?, deadline = ?, deadline_time = ? where id = ?", entry.title().trim(), entry.deadline(), entry.deadlineTime(), id);
        return Map.of("id", id);
    }

    @DeleteMapping("/topics/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<String,Object> deleteTopic(@PathVariable UUID id, Authentication auth) {
        requireTopic(id,auth);
        var rows=jdbc.queryForList("select t.import_key from syllabus_topics t join syllabuses s on s.id=t.syllabus_id where t.id=? and s.tenant_id=?",id,TenantContext.require());
        Object key=rows.get(0).get("import_key");
        jdbc.update("delete from syllabus_topics where id=?",id);
        if(key!=null) jdbc.update("delete from syllabus_import_keys where tenant_id=? and import_key=?",TenantContext.require(),key);
        return Map.of("deleted",true,"id",id);
    }

    public record TopicStatus(@NotBlank String status) {}
    @PutMapping("/topics/{id}/status")
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public Map<String,Object> status(@PathVariable UUID id, @Valid @RequestBody TopicStatus entry, Authentication auth) {
        var topic = requireTopic(id,auth);
        if(!Set.of("TODO","IN_PROGRESS","DONE").contains(entry.status())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose To do, In progress or Done");
        if(entry.status().equals("DONE") && topic.get("completed_at") != null) return Map.of("id",id);
        if(entry.status().equals("DONE")) {
            jdbc.update("""
                update syllabus_topics set workflow_status='DONE',completed_at=coalesce(completed_at,now()),completed_by=coalesce(completed_by,?),
                  days_late=greatest(0,(now() at time zone 'Asia/Kolkata')::date-deadline),
                  completion_status=case when (now() at time zone 'Asia/Kolkata')>deadline+deadline_time then 'LATE' else 'ON_TIME' end
                where id=?
                """,auth.getName(),id);
        } else jdbc.update("update syllabus_topics set workflow_status=?,completed_at=null,completed_by=null,points=0,days_late=0,completion_status=null where id=?",entry.status(),id);
        return Map.of("id",id);
    }

    @PostMapping("/topics/{id}/complete")
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public Map<String, Object> complete(@PathVariable UUID id, @Valid @RequestBody Completion entry, Authentication auth) {
        // A row lock keeps simultaneous submissions from overwriting the original completion.
        var topic = requireTopic(id, auth);
        if (topic.get("completed_at") != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This topic has already been completed");
        if (topic.get("start_date") != null && LocalDate.parse(topic.get("start_date").toString()).isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Topic has not started yet");
        jdbc.update("""
            update syllabus_topics set completed_at = now(), completed_by = ?,
            days_late = greatest(0, (now() at time zone 'Asia/Kolkata')::date - deadline),
            points = case when (now() at time zone 'Asia/Kolkata') > deadline + deadline_time
              then coalesce((select late from syllabus_point_rules where tenant_id=?),0)
              else coalesce((select on_time from syllabus_point_rules where tenant_id=?),5) end,
            completion_status = case when (now() at time zone 'Asia/Kolkata') > deadline + deadline_time then 'LATE' else 'ON_TIME' end
            where id = ?
            """, auth.getName(), TenantContext.require(), TenantContext.require(), id);
        return Map.of("id", id);
    }

    @PostMapping("/topics/{id}/notes")
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> addNote(@PathVariable UUID id, @Valid @RequestBody NoteInput entry, Authentication auth) {
        requireTopic(id, auth);
        UUID noteId = UUID.randomUUID();
        jdbc.update("insert into syllabus_notes(id, topic_id, author_id, body) values (?, ?, ?, ?)", noteId, id, auth.getName(), entry.body().trim());
        return Map.of("id", noteId);
    }

    public record CreateSyllabus(@NotBlank @Size(max = 180) String title,
        @NotBlank @Size(max = 20) String className, @NotBlank @Size(max = 10) String sectionName,
        @NotBlank @Size(max = 100) String subject, @NotEmpty @Size(max = 100) List<@NotNull @Valid TopicInput> topics) {}
    public record TopicInput(@NotBlank @Size(max = 200) String title, @NotNull LocalDate deadline, LocalTime deadlineTime) {
        public TopicInput { if (deadlineTime == null) deadlineTime = LocalTime.of(23, 59, 59); }
        public TopicInput(String title, LocalDate deadline) { this(title, deadline, null); }
    }
    public record AnnualSyllabus(@NotBlank @Size(max = 180) String title,
        @NotBlank @Size(max = 20) String className, @NotBlank @Size(max = 10) String sectionName,
        @NotNull LocalDate yearStart, @NotEmpty @Size(max = 30) List<@NotNull @Valid SubjectTargets> subjects,
        @Pattern(regexp = "FULL_YEAR|TERM_1|TERM_2|FINAL") String period) {
        public AnnualSyllabus { if (period == null) period = "FULL_YEAR"; }
        public AnnualSyllabus(String title, String className, String sectionName, LocalDate yearStart, List<SubjectTargets> subjects) {
            this(title, className, sectionName, yearStart, subjects, "FULL_YEAR");
        }
    }
    public record SubjectTargets(@NotBlank @Size(max = 100) String subject,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid TopicInput> topics, @Size(max=20) String teacherUserId) {
        public SubjectTargets(String subject,List<TopicInput> topics) { this(subject,topics,null); }
    }
    public record Completion(@NotNull @AssertTrue Boolean completed) {}
    public record NoteInput(@NotBlank @Size(max = 2000) String body) {}
}
