package com.edura.schoolos.student;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admissions")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class StudentAdmissionController {
    private static final List<String> HEADERS = List.of("Student Name", "Father Name", "Mother Name", "Date of Birth (DD-MM-YYYY)", "Parent Contact Number", "Roll Number", "Father Occupation", "Mother Occupation", "Parent Email", "Address", "Section", "Blood Group", "Aadhaar Number", "Transport Available (YES/NO)", "Student Status (NEW/OLD)", "Admission Number", "Date of Admission (DD-MM-YYYY)");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public StudentAdmissionController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) { this.jdbc = jdbc; this.passwordEncoder = passwordEncoder; }

    @PostMapping
    @Transactional
    public Map<String, Object> admit(@Valid @RequestBody AdmissionRequest request) {
        return create(request);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select id, display_name, user_id, admission_number, admission_date, roll_number, class_name, section_name, email, date_of_birth, father_name, mother_name, parent_contact_number, profile_image, registration_type from students where tenant_id = ? and active = true order by created_at desc", TenantContext.require());
    }

    @GetMapping(value = "/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(IndexedColors.INDIGO.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex()); style.setFont(font);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd-mm-yyyy"));
            for (int classNumber = 1; classNumber <= 10; classNumber++) {
                Sheet sheet = workbook.createSheet("Class " + classNumber);
                Row header = sheet.createRow(0);
                for (int i = 0; i < HEADERS.size(); i++) { Cell cell = header.createCell(i); cell.setCellValue(HEADERS.get(i)); cell.setCellStyle(style); sheet.setColumnWidth(i, i == 9 ? 34 * 256 : 22 * 256); }
                header.setHeightInPoints(26);
                sheet.createFreezePane(0, 1);
                DataValidationHelper validation = sheet.getDataValidationHelper();
                DataValidationConstraint statusConstraint = validation.createExplicitListConstraint(new String[]{"NEW", "OLD"});
                DataValidation statusValidation = validation.createValidation(statusConstraint, new org.apache.poi.ss.util.CellRangeAddressList(1, 200, 14, 14));
                statusValidation.setSuppressDropDownArrow(false);
                sheet.addValidationData(statusValidation);
                DataValidationConstraint dobConstraint = validation.createDateConstraint(DataValidationConstraint.OperatorType.BETWEEN, "DATE(1900,1,1)", "TODAY()", "dd-mm-yyyy");
                DataValidation dobValidation = validation.createValidation(dobConstraint, new org.apache.poi.ss.util.CellRangeAddressList(1, 200, 3, 3));
                dobValidation.setEmptyCellAllowed(true);
                dobValidation.setShowErrorBox(true);
                dobValidation.setErrorStyle(DataValidation.ErrorStyle.STOP);
                dobValidation.createErrorBox("Check date of birth", "Enter a valid date not later than today.");
                dobValidation.setShowPromptBox(true);
                dobValidation.createPromptBox("Date of birth", "In Excel for the web, double-click this cell for the calendar. Otherwise enter DD-MM-YYYY.");
                sheet.addValidationData(dobValidation);
                DataValidationConstraint admissionDateConstraint = validation.createDateConstraint(DataValidationConstraint.OperatorType.BETWEEN, "DATE(1900,1,1)", "TODAY()", "dd-mm-yyyy");
                DataValidation admissionDateRule = validation.createValidation(admissionDateConstraint, new org.apache.poi.ss.util.CellRangeAddressList(1, 200, 16, 16));
                admissionDateRule.setEmptyCellAllowed(true);
                admissionDateRule.setShowErrorBox(true);
                admissionDateRule.setErrorStyle(DataValidation.ErrorStyle.STOP);
                admissionDateRule.createErrorBox("Check date of admission", "Enter a valid date not later than today.");
                admissionDateRule.setShowPromptBox(true);
                admissionDateRule.createPromptBox("Date of admission", "In Excel for the web, double-click this cell for the calendar. Otherwise enter DD-MM-YYYY.");
                sheet.addValidationData(admissionDateRule);
                for (int row = 1; row <= 200; row++) {
                    Row dataRow = sheet.createRow(row);
                    dataRow.createCell(3).setCellStyle(dateStyle);
                    dataRow.createCell(16).setCellStyle(dateStyle);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException error) { throw new IllegalStateException("Could not create admission template", error); }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String, Object> importExcel(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a completed Excel workbook.");
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            DataFormatter formatter = new DataFormatter();
            List<Map<String, Object>> imported = new ArrayList<>();
            int skipped = 0;
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                java.util.regex.Matcher classTab = java.util.regex.Pattern.compile("(?i)^Class\\s+(10|[1-9])$").matcher(sheet.getSheetName().trim());
                boolean classSheet = classTab.matches();
                String sheetClass = classSheet ? classTab.group(1) : null;
                for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null || cell(formatter, row, 0).isBlank()) continue;
                    String dobText = cell(formatter, row, 3);
                    LocalDate dob;
                    try { dob = parseDateOfBirth(dobText); } catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, sheet.getSheetName() + " row " + (rowIndex + 1) + ": date of birth must use DD-MM-YYYY."); }
                    boolean legacyAdmissionsSheet = !classSheet && cell(formatter, sheet.getRow(0), 10).equalsIgnoreCase("Class");
                    if (!classSheet && !legacyAdmissionsSheet) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a Class 1 to Class 10 worksheet, or the older Admissions template.");
                    int sectionColumn = legacyAdmissionsSheet ? 11 : 10;
                    int bloodGroupColumn = legacyAdmissionsSheet ? 12 : 11;
                    int aadhaarColumn = legacyAdmissionsSheet ? 13 : 12;
                    int transportColumn = legacyAdmissionsSheet ? 14 : 13;
                    int statusColumn = legacyAdmissionsSheet ? 15 : 14;
                    String admissionNumber = classSheet ? cell(formatter, row, 15) : "";
                    String admissionDateText = classSheet ? cell(formatter, row, 16) : "";
                    LocalDate admissionDate = null;
                    if (!admissionDateText.isBlank()) {
                        try { admissionDate = parseDate(admissionDateText); }
                        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, sheet.getSheetName() + " row " + (rowIndex + 1) + ": date of admission must use DD-MM-YYYY."); }
                    }
                    String className = legacyAdmissionsSheet ? cell(formatter, row, 10) : sheetClass;
                    AdmissionRequest request = new AdmissionRequest(cell(formatter,row,0), cell(formatter,row,1), cell(formatter,row,2), dob,
                        cell(formatter,row,4), cell(formatter,row,5), cell(formatter,row,6), cell(formatter,row,7), cell(formatter,row,8),
                        cell(formatter,row,9), className, cell(formatter,row,sectionColumn), cell(formatter,row,bloodGroupColumn), cell(formatter,row,aadhaarColumn),
                        cell(formatter,row,transportColumn).equalsIgnoreCase("YES"), null, null, cell(formatter,row,statusColumn).isBlank() ? "OLD" : cell(formatter,row,statusColumn), admissionNumber, admissionDate);
                    if (alreadyAdmitted(request)) skipped++;
                    else imported.add(create(request));
                }
            }
            if (imported.isEmpty() && skipped == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The workbook has no student rows to import.");
            return Map.of("created", imported.size(), "skipped", skipped, "students", imported);
        } catch (ResponseStatusException error) { throw error; }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the workbook. Upload the downloaded .xlsx template."); }
    }

    private String cell(DataFormatter formatter, Row row, int index) { return row.getCell(index) == null ? "" : formatter.formatCellValue(row.getCell(index)).trim(); }

    private LocalDate parseDateOfBirth(String value) {
        return parseDate(value);
    }

    private LocalDate parseDate(String value) {
        try { return LocalDate.parse(value, DateTimeFormatter.ofPattern("dd-MM-uuuu")); }
        catch (Exception ignored) { return LocalDate.parse(value); }
    }

    private boolean alreadyAdmitted(AdmissionRequest request) {
        String phoneDigits = request.parentContactNumber() == null ? "" : request.parentContactNumber().replaceAll("\\D", "");
        Boolean exists = jdbc.queryForObject("""
            select exists(select 1 from students where tenant_id = ? and (
                (? <> '' and lower(trim(admission_number)) = lower(trim(?)))
                or
                (lower(trim(display_name)) = lower(trim(?)) and date_of_birth = ? and regexp_replace(coalesce(parent_contact_number, ''), '[^0-9]', '', 'g') = ?)
                or (lower(trim(coalesce(roll_number, ''))) = lower(trim(?)) and lower(trim(coalesce(class_name, ''))) = lower(trim(?))
                    and lower(trim(coalesce(section_name, ''))) = lower(trim(coalesce(?, ''))))
            ))
            """, Boolean.class, TenantContext.require(), request.admissionNumber() == null ? "" : request.admissionNumber().trim(), request.admissionNumber() == null ? "" : request.admissionNumber().trim(), request.name(), request.dateOfBirth(), phoneDigits,
            request.rollNumber(), request.className(), request.sectionName());
        return Boolean.TRUE.equals(exists);
    }

    private Map<String, Object> create(AdmissionRequest request) {
        if (alreadyAdmitted(request)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This student already has a profile. Check the name, date of birth, parent contact, and class roll number.");
        if (request.name() == null || request.name().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Student name is required.");
        if (request.dateOfBirth() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date of birth is required.");
        if (request.parentContactNumber() == null || request.parentContactNumber().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent contact number is required.");
        if (request.rollNumber() == null || request.rollNumber().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Roll number is required.");
        if (request.className() == null || request.className().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Class is required.");
        UUID tenant = TenantContext.require();
        UUID id = UUID.randomUUID();
        String userCode = nextUserCode(tenant);
        String registrationType = "NEW".equalsIgnoreCase(request.registrationType()) ? "NEW" : "OLD";
        String password = request.dateOfBirth().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        String admissionNumber = blankToNull(request.admissionNumber());
        jdbc.update("insert into students(id,tenant_id,admission_number,display_name,email,password_hash,user_id,roll_number,class_name,section_name,date_of_birth,admission_date,transport_enabled,registration_type,father_name,mother_name,father_occupation,mother_occupation,parent_contact_number,parent_email,address,blood_group,aadhaar_number,profile_image,aadhaar_attachment) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, tenant, admissionNumber == null ? userCode : admissionNumber, request.name().trim(), null, passwordEncoder.encode(password), userCode, request.rollNumber().trim(), request.className().trim(), blankToNull(request.sectionName()), request.dateOfBirth(), request.admissionDate(), request.transportAvailable(), registrationType, blankToNull(request.fatherName()), blankToNull(request.motherName()), blankToNull(request.fatherOccupation()), blankToNull(request.motherOccupation()), request.parentContactNumber().trim(), blankToNull(request.parentEmail()), blankToNull(request.address()), blankToNull(request.bloodGroup()), blankToNull(request.aadhaarNumber()), blankToNull(request.photo()), blankToNull(request.aadhaarAttachment()));
        return Map.of("id", id, "userId", userCode, "name", request.name(), "rollNumber", request.rollNumber(), "className", request.className(), "sectionName", request.sectionName() == null ? "" : request.sectionName(), "registrationType", registrationType);
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String nextUserCode(UUID tenant) {
        for (int attempt = 0; attempt < 30; attempt++) {
            String code = "ST" + String.format("%06d", random.nextInt(1_000_000));
            Long count = jdbc.queryForObject("select count(*) from students where tenant_id = ? and user_id = ?", Long.class, tenant, code);
            if (count == null || count == 0) return code;
        }
        throw new IllegalStateException("Could not generate a unique student user ID");
    }

    public record AdmissionRequest(@NotBlank String name, String fatherName, String motherName, LocalDate dateOfBirth,
        @NotBlank String parentContactNumber, @NotBlank String rollNumber, String fatherOccupation, String motherOccupation,
        String parentEmail, String address, @NotBlank String className, String sectionName, String bloodGroup,
        String aadhaarNumber, boolean transportAvailable, String photo, String aadhaarAttachment, String registrationType, String admissionNumber, LocalDate admissionDate) {}
}
