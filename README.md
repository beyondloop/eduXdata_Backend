# Edura School OS API

Spring Boot modular monolith matching the supplied School ERP architecture.

## Included foundation

- JWT authentication boundary with role claims for `ADMIN`, `TEACHER`, `PARENT`, and `STUDENT`.
- Tenant context derived from the authenticated JWT `tenant_id` claim; client-supplied tenant IDs are not trusted.
- PostgreSQL + Flyway schema for schools, academic years, users, classes, sections, students, guardians, attendance, homework, exams, results, fees, payments, announcements, and audit logs.
- Redis configuration for caching and future notification/report/AI workers.
- CORS, health/metrics endpoints, upload limits, UTC persistence, and environment-based configuration.

## Run locally

From the repository root, start PostgreSQL and Redis with `docker compose up -d postgres redis`, then run:

```powershell
cd backend
.\start.ps1
```

The PowerShell launcher checks whether a healthy backend already occupies the configured port
(8080 by default). If so, it reuses that server instead of starting a conflicting second instance.
Otherwise, it runs `mvn spring-boot:run` in the current terminal. Keep that terminal open.

## Syllabus and lesson planning

Admins use **Syllabus → Assigned Syllabus → Upload syllabus** to select an existing
Teacher Workload class, section and subject, optionally attach a PDF (up to 5 MB),
and enter topics with individual deadlines. PDF contents are reference material;
topics and deadlines are entered in the form. Each syllabus tracks one class and
section so completion in one section does not complete another section's work.

Teachers see their currently assigned class/subject syllabuses under
**Academic → Lesson Planner**. They can download the PDF, save dated daily notes,
and check a topic before submitting completion. Deadlines use the selected date and
time in Asia/Kolkata; existing date-only deadlines keep their end-of-day cutoff. Late submissions are accepted and marked
**Completed late**. Notes and completion retain the submitting teacher's identity.
An unfinished topic with notes is **In progress**, or **Overdue** after its deadline.
Admins can inspect notes and filter unfinished/completed topics, and can revise
unfinished topic names and deadlines. Completed topics are locked to preserve the
record of their deadline and submission.

Migration `V34__syllabus_tracking.sql` creates the syllabus, topic and note tables.
Restart the backend after updating the code to load `/api/v1/syllabuses` endpoints.
Teacher access follows current active workload assignments; admin reads stay within
the signed-in school. PDFs are stored in PostgreSQL and downloaded through the same
authorization checks.

Run the PostgreSQL integration checks against the configured local database:

```powershell
$env:SYLLABUS_INTEGRATION_TESTS = 'true'
mvn '-Dtest=SyllabusIntegrationTest' test
```

The checks apply pending Flyway migrations and roll back their test records.

### Full-year monthly targets

The default upload mode is **Full year · All subjects**. Select a class/section,
starting year and first month (April by default), then expand each assigned subject
to plan its twelve months. Each month accepts multiple topics with a target date and
time in IST. Every subject needs at least one target; non-teaching months can remain
empty. An optional class PDF is attached to each subject's syllabus. A single publish
request saves all subject plans in one transaction. The single-subject form remains
available for individual plans.

Admins and teachers can filter by academic year, subject and target month and view
monthly completion totals. Dates must remain inside the selected academic year,
including when an admin revises a deadline. Migration `V35__annual_syllabus_targets.sql`
adds the academic year start and topic deadline time. Annual uploads use
`POST /api/v1/syllabuses/annual` with a JSON `syllabus` part and optional PDF `file` part.

### Terms and PDF subject detection

The **Year / Term syllabus** form supports Full year, Term 1 (the first six academic
months) and Term 2 (the following six). For an April start these are April–September
and October–March. Each period has its own PDF, targets and completion records.
Teachers see the period labels and filters in Lesson Planner. Deadlines are validated
against the selected term when creating or updating topics. Existing syllabuses stay
under Full year. Migration `V36__syllabus_terms.sql` stores the period.

Uploading a PDF automatically calls the admin-only `/api/v1/syllabuses/extract-subjects`
endpoint with `file`, `className` and `sectionName`. It reads local PDF text using
[Apache PDFBox](https://pdfbox.apache.org/3.0/getting-started.html), matches complete
subject names and common aliases (such as Maths/Mathematics and EVS) against the class's
active workload subjects, and selects matches for review. Admins can select or deselect
subjects before publishing. Files are not sent to an external extraction service.
Monthly topics and deadlines are entered separately. Extraction accepts PDFs up to
5 MB and 100 pages; locked, invalid, scanned/image-only or unmatched documents show
a manual-selection fallback. Scanned documents require OCR before automatic detection.

## Reloading the backend

After backend code changes, restart this project's server to load the new endpoints:

```powershell
.\start.ps1 -Restart
```

If `mvn spring-boot:run` reports `Process terminated with exit code: 1`, check the earlier
startup error. `Port 8080 was already in use` means a server is already running; use
`.\start.ps1` to reuse it, or `.\start.ps1 -Restart` to reload the current code.

Demo login endpoint:

```http
POST http://localhost:8080/api/v1/auth/login
Content-Type: application/json

{"username":"admin@springfield.edu","password":"local-dev","role":"ADMIN"}
```

This returns a JWT for local UI integration. Replace the demo adapter in `AuthController` with the `users` repository/password flow before production use. The schema and tenant boundary are production-oriented; credentials are intentionally not hardcoded as a real authentication system.

### Class-wise syllabus Excel import

Admin > Syllabus includes template download, workbook validation, row preview, and explicit confirmation. Templates contain Instructions and Class 1 through Class 12, with the requested eleven columns (the header `TeacherEmployeelD` is preserved exactly). Employee codes resolve against `teachers.teacher_id` and must have an active matching workload. Existing PDF and annual syllabus flows remain available.

AcademicYear is YYYY-YY and uses April 1�March 31. Dates accept ISO text or Excel date cells. Priority is LOW/MEDIUM/HIGH. Descriptions are optional; other fields are required. Files are limited to 5 MB and 2000 topics. Formula/error cells, invalid assignments, date ranges, conflicting subject codes, and duplicate topics are rejected. Duplicate identity is case-insensitive year/class/section/subject code/chapter/topic. Imports append; they do not overwrite completed work.

Previews are stored server-side, scoped to tenant and creating admin, expire after 30 minutes, and are revalidated during atomic confirmation. Replays and concurrent duplicate imports are prevented. A new valid preview replaces that admin's previous pending preview. Expired previews are cleaned during subsequent valid uploads.

Teachers use Lesson Planner. Imported plans require both the named teacher and an active matching workload. Reassigning an imported plan requires updating its assigned teacher; workload transfer alone does not grant access. Completion is locked against repeated submissions and records server time in IST, ON_TIME/LATE status, calendar days late, and 10/5 points respectively. Future topics cannot be completed before their start date. Students use Syllabus and can read only their active class/section's progress. Admin's syllabus dashboard summarizes classes and teachers; points remain credited to the teacher who completed the topic.

Migration V37 extends the existing syllabus tables and backfills completion metrics. Run backend tests with `SYLLABUS_INTEGRATION_TESTS=true` against the configured local PostgreSQL database to include import lifecycle, tenant/role isolation, student scope, and completion checks. Test data rolls back; Flyway schema migrations persist. Frontend validation: `npm run build` and ESLint on the syllabus components.
