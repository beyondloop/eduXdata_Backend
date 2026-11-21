package com.edura.schoolos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.Set;
import java.util.List;

@Component
public class TenantContextFilter extends OncePerRequestFilter {
    private static final Set<String> SUB_ADMIN_DEFAULTS = Set.of("Overview", "All Students", "Classes", "Sections", "Student Performance", "Student Documents", "Admission Enquiry", "Student Admission", "Identity Card", "Admit Cards", "Documents", "Reports");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TenantContextFilter(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken token) {
                Jwt jwt = token.getToken();
                String tenantId = jwt.getClaimAsString("tenant_id");
                if (tenantId == null || tenantId.isBlank()) { response.sendError(403, "Tenant claim is required"); return; }
                TenantContext.set(UUID.fromString(tenantId));
                boolean subAdmin = token.getAuthorities().stream().anyMatch(authority -> "ROLE_SUB_ADMIN".equals(authority.getAuthority()));
                if (subAdmin && !hasModuleAccess(request.getRequestURI(), request.getMethod(), token.getName())) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "This module is not enabled for your account");
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally { TenantContext.clear(); }
    }

    private boolean hasModuleAccess(String path, String method, String userId) {
        if (path.equals("/error") || path.equals("/api/v1/permissions/me") || path.equals("/api/v1/tenant/context")) return true;
        Set<String> allowedFeatures = featuresForPath(path, method);
        if (allowedFeatures.isEmpty()) return false;
        List<List<String>> rows = jdbc.query("select features::text from portal_permissions where tenant_id = ? and target_role = 'SUB_ADMIN' and target_user_id = ?",
            (rs, row) -> {
                try { return mapper.readValue(rs.getString(1), new TypeReference<List<String>>() {}); }
                catch (IOException error) { throw new IllegalStateException("Saved SubAdmin permissions could not be read", error); }
            }, TenantContext.require(), userId);
        return rows.isEmpty() ? allowedFeatures.stream().anyMatch(SUB_ADMIN_DEFAULTS::contains) : allowedFeatures.stream().anyMatch(rows.get(0)::contains);
    }

    private Set<String> featuresForPath(String path, String method) {
        if (path.startsWith("/api/v1/dashboard/") || path.equals("/api/v1/admin/overview")) return Set.of("Overview");
        if (path.startsWith("/api/v1/fee-collections")) return Set.of("Fee Collection");
        if (path.startsWith("/api/v1/student-fee-structures")) return Set.of("Fee Structure");
        if (path.startsWith("/api/v1/admission-enquiries")) return Set.of("Admission Enquiry");
        if (path.startsWith("/api/v1/admissions")) return Set.of("Student Admission", "Identity Card", "Admit Cards");
        if (path.equals("/api/v1/students") || path.startsWith("/api/v1/students/")) return Set.of("All Students", "Student Documents", "Student Performance");
        if (path.equals("/api/v1/classes") || path.startsWith("/api/v1/classes/") || path.equals("/api/v1/sections") || path.startsWith("/api/v1/sections/")) {
            if ("GET".equals(method) && (path.equals("/api/v1/classes") || path.equals("/api/v1/sections"))) return Set.of("All Students", "Classes", "Sections", "Fee Structure");
            return Set.of("All Students", "Classes", "Sections");
        }
        if (path.startsWith("/api/v1/admin/documents") || path.startsWith("/api/v1/admin/records/documents")) return Set.of("Documents", "Student Documents");
        if (path.startsWith("/api/v1/admin/records/student-documents")) return Set.of("Student Documents");
        if (path.startsWith("/api/v1/admin/records/reports")) return Set.of("Reports");
        if (path.startsWith("/api/v1/admin/records/performance") || path.startsWith("/api/v1/admin/students/")) return Set.of("Student Performance");
        if (path.startsWith("/api/v1/admin/records/attendance-analytics")) return Set.of("Attendance Analytics");
        if (path.startsWith("/api/v1/admin/records/attendance")) return Set.of("Today's Attendance", "Student Attendance", "Attendance Reports");
        if (path.startsWith("/api/v1/admin/records/exams")) return Set.of("Exams");
        if (path.startsWith("/api/v1/admin/records/results")) return Set.of("Marks", "Results");
        if (path.startsWith("/api/v1/admin/facility/")) return Set.of("Facility", "Inventory");
        if (path.startsWith("/api/v1/admin/transport/")) return Set.of("Transport");
        if (path.startsWith("/api/v1/discipline")) return Set.of("Discipline Points");
        if (path.startsWith("/api/v1/teachers")) return Set.of("All Teachers", "Teacher Adjustments", "Teacher Performance", "Teacher Workload");
        if (path.startsWith("/api/v1/teacher-adjustments")) return Set.of("Teacher Adjustments");
        if (path.startsWith("/api/v1/teacher-performance")) return Set.of("Teacher Performance");
        if (path.startsWith("/api/v1/teacher-workloads")) return Set.of("Teacher Workload");
        if (path.startsWith("/api/v1/attendance")) return Set.of("Today's Attendance", "Student Attendance", "Teachers Attendance", "Attendance Analytics", "Manual Correction", "Attendance Reports");
        if (path.startsWith("/api/v1/teacher-attendance")) return Set.of("Teachers Attendance", "Leave Approvals");
        if (path.startsWith("/api/v1/teacher-leave") || path.startsWith("/api/v1/student-leave")) return Set.of("Leave Approvals");
        if (path.startsWith("/api/v1/admin/academics") || path.startsWith("/api/v1/admin/subjects") || path.startsWith("/api/v1/exam-timetable")) return Set.of("Exams", "Marks", "Results");
        if (path.startsWith("/api/v1/syllabuses") || path.startsWith("/api/v1/syllabus-subjects")) return Set.of("Assigned Syllabus", "Pending", "Completed");
        if (path.startsWith("/api/v1/announcements")) return Set.of("Announcements");
        if (path.startsWith("/api/v1/notifications")) return Set.of("Notifications");
        if (path.startsWith("/api/v1/holidays")) return Set.of("Holiday List");
        if (path.startsWith("/api/v1/admin/separations")) return Set.of("Separation Portal");
        if (path.startsWith("/api/v1/admin/profile")) return Set.of("Profile");
        if (path.startsWith("/api/v1/teacher-salary-structures")) return Set.of("Salary");
        if (path.startsWith("/api/v1/payroll/")) return Set.of("Salary");
        return Set.of();
    }
}
