package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/exam-timetable")
public class ExamTimetableController {
    private static final Set<String> TERMS = Set.of("Term 1", "Term 2", "Final");
    private final JdbcTemplate jdbc;

    public ExamTimetableController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER','STUDENT')")
    public List<Map<String, Object>> timetables() {
        return jdbc.queryForList("select id, exam_term, file_name, updated_at from exam_timetables where tenant_id = ? order by exam_term", TenantContext.require());
    }

    @GetMapping("/template")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<byte[]> template(Authentication auth) throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var instructions = workbook.createSheet("Instructions");
            instructions.createRow(0).createCell(0).setCellValue("Enter one exam per row. Select a date in the Date column, choose a subject from its dropdown, and enter the start time in the Time column.");
            instructions.createRow(1).createCell(0).setCellValue("Use the Date cell dropdown to choose a date. Excel .xlsx cells do not provide a built-in calendar popup. You can type another date as YYYY-MM-DD and confirm Excel's warning. Time accepts entries such as 9:30 AM.");
            instructions.setColumnWidth(0, 30000);
            var sheet = workbook.createSheet("Exam Schedule");
            var subjectList = workbook.createSheet("Subject Options");
            var dateList = workbook.createSheet("Date Options");
            var subjects = jdbc.queryForList("select distinct subject from teacher_workloads where tenant_id = ? and teacher_user_id = ? and status = 'ACTIVE' and trim(subject) <> '' order by subject", String.class, TenantContext.require(), auth.getName());
            if (subjects.isEmpty()) subjects = jdbc.queryForList("select distinct name from subjects where tenant_id = ? order by name", String.class, TenantContext.require());
            if (subjects.isEmpty()) subjects = List.of("Other");
            for (int i = 0; i < subjects.size(); i++) subjectList.createRow(i).createCell(0).setCellValue(subjects.get(i));
            workbook.setSheetVisibility(workbook.getSheetIndex(subjectList), SheetVisibility.VERY_HIDDEN);
            Name subjectRange = workbook.createName();
            subjectRange.setNameName("SubjectOptions");
            subjectRange.setRefersToFormula("'Subject Options'!$A$1:$A$" + subjects.size());
            LocalDate firstDate = LocalDate.now().withDayOfYear(1).minusYears(1);
            LocalDate lastDate = firstDate.plusYears(7).minusDays(1);
            var dateOptionStyle = workbook.createCellStyle();
            dateOptionStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            int dateCount = 0;
            for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
                Cell dateCell = dateList.createRow(dateCount).createCell(0);
                dateCell.setCellValue(java.sql.Date.valueOf(date));
                dateCell.setCellStyle(dateOptionStyle);
                dateCount++;
            }
            Name dateRange = workbook.createName();
            dateRange.setNameName("DateOptions");
            dateRange.setRefersToFormula("'Date Options'!$A$1:$A$" + dateCount);
            String[] headers = {"Date", "Subject", "Time"};
            var header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.INDIGO.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex()); header.setFont(font);
            var dateStyle = workbook.createCellStyle(); dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            var timeStyle = workbook.createCellStyle(); timeStyle.setDataFormat(workbook.createDataFormat().getFormat("h:mm AM/PM"));
            Row headings = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) { Cell cell = headings.createCell(i); cell.setCellValue(headers[i]); cell.setCellStyle(header); sheet.setColumnWidth(i, i == 1 ? 8000 : 5000); }
            sheet.createFreezePane(0, 1);
            for (int r = 1; r <= 200; r++) { sheet.createRow(r).createCell(0).setCellStyle(dateStyle); sheet.getRow(r).createCell(2).setCellStyle(timeStyle); }
            DataValidationHelper validation = sheet.getDataValidationHelper();
            DataValidationConstraint dateConstraint = validation.createFormulaListConstraint("DateOptions");
            DataValidation dateValidation = validation.createValidation(dateConstraint, new CellRangeAddressList(1, 200, 0, 0));
            dateValidation.setShowErrorBox(true); dateValidation.createErrorBox("Choose a date", "Choose from the dropdown or type another date and confirm the Excel warning.");
            dateValidation.setErrorStyle(DataValidation.ErrorStyle.WARNING); dateValidation.setSuppressDropDownArrow(false); sheet.addValidationData(dateValidation);
            DataValidationConstraint subjectConstraint = validation.createFormulaListConstraint("SubjectOptions");
            DataValidation subjectValidation = validation.createValidation(subjectConstraint, new CellRangeAddressList(1, 200, 1, 1));
            subjectValidation.setShowErrorBox(true); subjectValidation.setSuppressDropDownArrow(false);
            subjectValidation.createErrorBox("Subject not listed", "Choose a subject from the dropdown or enter Other."); sheet.addValidationData(subjectValidation);
            DataValidationConstraint timeConstraint = validation.createTimeConstraint(DataValidationConstraint.OperatorType.BETWEEN, "TIME(0,0,0)", "TIME(23,59,0)");
            DataValidation timeValidation = validation.createValidation(timeConstraint, new CellRangeAddressList(1, 200, 2, 2));
            timeValidation.setShowErrorBox(true); timeValidation.createErrorBox("Invalid time", "Enter a valid time, such as 9:30 AM."); sheet.addValidationData(timeValidation);
            workbook.setSheetVisibility(workbook.getSheetIndex(subjectList), SheetVisibility.VERY_HIDDEN);
            workbook.setSheetVisibility(workbook.getSheetIndex(dateList), SheetVisibility.VERY_HIDDEN);
            workbook.write(output);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=exam-timetable-template.xlsx")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(output.toByteArray());
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public Map<String, Object> upload(@RequestParam String term, @RequestPart("file") MultipartFile file, Authentication auth) throws IOException {
        if (!TERMS.contains(term)) throw bad("Choose Term 1, Term 2 or Final");
        if (file.isEmpty() || file.getSize() > 5L * 1024 * 1024 || !Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw bad("Choose an .xlsx workbook up to 5 MB");
        List<ScheduleRow> rows = parse(file.getBytes());
        UUID tenant = TenantContext.require();
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("exam-timetable.xlsx").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^a-zA-Z0-9._ -]", "_");
        if (name.isBlank()) name = "exam-timetable.xlsx";
        jdbc.update("delete from exam_schedule_items where tenant_id = ? and exam_term = ?", tenant, term);
        for (ScheduleRow row : rows) jdbc.update("insert into exam_schedule_items(tenant_id, exam_term, subject, exam_date, exam_time) values (?, ?, ?, ?, ?)", tenant, term, row.subject(), row.date(), row.time());
        jdbc.update("""
            insert into exam_timetables(id, tenant_id, exam_term, file_name, file_data, uploaded_by)
            values (?, ?, ?, ?, ?, ?)
            on conflict (tenant_id, exam_term) do update set file_name=excluded.file_name, file_data=excluded.file_data,
              uploaded_by=excluded.uploaded_by, updated_at=now()
            """, UUID.randomUUID(), tenant, term, name.substring(0, Math.min(180, name.length())), file.getBytes(), auth.getName());
        return Map.of("saved", true, "rows", rows.size(), "term", term);
    }

    @GetMapping("/file")
    @PreAuthorize("hasAnyRole('TEACHER','STUDENT')")
    public ResponseEntity<byte[]> download(@RequestParam String term) throws IOException {
        if (!TERMS.contains(term)) throw bad("Choose a valid exam term");
        UUID tenant = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList("select subject, exam_date, exam_time from exam_schedule_items where tenant_id = ? and exam_term = ? order by exam_date, exam_time, subject", tenant, term);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam timetable has not been uploaded");
        String school = jdbc.queryForObject("select name from tenants where id = ?", String.class, tenant);
        byte[] pdf = createTimetablePdf(school, term, rows);
        String filename = "exam-" + term.toLowerCase(Locale.ROOT).replace(' ', '-') + "-timetable.pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(pdf);
    }

    private byte[] createTimetablePdf(String school, String term, List<Map<String, Object>> rows) throws IOException {
        final int perPage = 12;
        final float width = PDRectangle.A4.getWidth();
        final float height = PDRectangle.A4.getHeight();
        final float left = 44;
        final float tableWidth = width - left * 2;
        int pageCount = (rows.size() + perPage - 1) / perPage;
        var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        var ink = new Color(31, 45, 78);
        var muted = new Color(104, 119, 148);
        var navy = new Color(28, 40, 78);
        var indigo = new Color(91, 82, 224);
        var pale = new Color(245, 247, 255);
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    fill(stream, navy, 0, height - 190, width, 190);
                    fill(stream, new Color(72, 190, 213), 0, height - 196, width, 6);
                    text(stream, bold, 11, Color.WHITE, left, height - 54, fit(clean(school), bold, 11, tableWidth));
                    text(stream, regular, 8, new Color(202, 211, 239), left, height - 74, "OFFICIAL EXAMINATION SCHEDULE");
                    text(stream, bold, 25, Color.WHITE, left, height - 119, "Exam timetable");
                    fill(stream, indigo, left, height - 164, 104, 25);
                    text(stream, bold, 10, Color.WHITE, left + 12, height - 155, term);
                    text(stream, regular, 8, new Color(202, 211, 239), left + 120, height - 155, rows.size() + " scheduled papers");

                    float headerY = height - 244;
                    fill(stream, new Color(237, 240, 252), left, headerY, tableWidth, 31);
                    text(stream, bold, 8, muted, left + 12, headerY + 11, "DATE");
                    text(stream, bold, 8, muted, left + 135, headerY + 11, "SUBJECT / PAPER");
                    text(stream, bold, 8, muted, left + tableWidth - 94, headerY + 11, "TIME");

                    int start = pageIndex * perPage;
                    int end = Math.min(rows.size(), start + perPage);
                    float rowHeight = 39;
                    for (int index = start; index < end; index++) {
                        float rowTop = headerY - (index - start) * rowHeight;
                        float rowBottom = rowTop - rowHeight;
                        if ((index - start) % 2 == 0) fill(stream, new Color(250, 251, 255), left, rowBottom, tableWidth, rowHeight);
                        strokeLine(stream, new Color(229, 233, 244), left, rowBottom, left + tableWidth, rowBottom);
                        Map<String, Object> row = rows.get(index);
                        Object rawDate = row.get("exam_date");
                        LocalDate date = rawDate instanceof LocalDate localDate ? localDate : rawDate instanceof java.sql.Date sqlDate ? sqlDate.toLocalDate() : LocalDate.parse(String.valueOf(rawDate));
                        String dateLabel = date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));
                        String subject = clean(String.valueOf(row.get("subject")));
                        String time = clean(String.valueOf(row.get("exam_time")));
                        text(stream, bold, 9, ink, left + 12, rowBottom + 15, dateLabel);
                        text(stream, regular, 10, ink, left + 135, rowBottom + 15, fit(subject, regular, 10, tableWidth - 250));
                        text(stream, bold, 9, indigo, left + tableWidth - 94, rowBottom + 15, fit(time, bold, 9, 82));
                    }
                    float footerY = 36;
                    strokeLine(stream, new Color(229, 233, 244), left, footerY + 11, left + tableWidth, footerY + 11);
                    text(stream, regular, 8, muted, left, footerY, "Please arrive at the examination venue on time.");
                    String pageLabel = "Page " + (pageIndex + 1) + " of " + pageCount;
                    text(stream, regular, 8, muted, width - left - regular.getStringWidth(pageLabel) / 1000 * 8, footerY, pageLabel);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private String fit(String value, PDType1Font font, float size, float maxWidth) throws IOException {
        String result = value;
        while (!result.isEmpty() && font.getStringWidth(result) / 1000 * size > maxWidth) result = result.substring(0, result.length() - 1);
        return result.equals(value) ? result : (result.length() > 3 ? result.substring(0, result.length() - 3) : result) + "...";
    }

    private void text(PDPageContentStream stream, PDType1Font font, float size, Color color, float x, float y, String value) throws IOException {
        stream.beginText(); stream.setFont(font, size); stream.setNonStrokingColor(color); stream.newLineAtOffset(x, y); stream.showText(clean(value)); stream.endText();
    }

    private void fill(PDPageContentStream stream, Color color, float x, float y, float width, float height) throws IOException {
        stream.setNonStrokingColor(color); stream.addRect(x, y, width, height); stream.fill();
    }

    private void strokeLine(PDPageContentStream stream, Color color, float x1, float y1, float x2, float y2) throws IOException {
        stream.setStrokingColor(color); stream.moveTo(x1, y1); stream.lineTo(x2, y2); stream.stroke();
    }

    private List<ScheduleRow> parse(byte[] bytes) {
        var rows = new ArrayList<ScheduleRow>();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Exam Schedule");
            if (sheet == null) sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) throw bad("Workbook must contain a sheet with Date, Subject and Time columns");
            Row header = sheet.getRow(0);
            if (!"date".equalsIgnoreCase(value(header, 0)) || !"subject".equalsIgnoreCase(value(header, 1)) || !"time".equalsIgnoreCase(value(header, 2)))
                throw bad("Use the template columns in this order: Date, Subject, Time");
            if (sheet.getLastRowNum() > 200) throw bad("Maximum 200 exam schedule rows per workbook");
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || (value(row, 0).isBlank() && value(row, 1).isBlank() && value(row, 2).isBlank())) continue;
                String subject = value(row, 1);
                String time = timeValue(row.getCell(2));
                if (subject.isBlank() || subject.length() > 160 || time.isBlank() || time.length() > 40)
                    throw bad("Row " + (i + 1) + " needs a subject (up to 160 characters) and time");
                rows.add(new ScheduleRow(dateValue(row.getCell(0), i + 1), subject, time));
            }
        } catch (ResponseStatusException failure) { throw failure; }
        catch (Exception failure) { throw bad("Could not read the workbook. Use the .xlsx timetable template and enter dates as YYYY-MM-DD."); }
        if (rows.isEmpty()) throw bad("The workbook has no schedule rows. Add at least one subject, date and time.");
        return rows;
    }

    private String value(Row row, int column) {
        Cell cell = row == null ? null : row.getCell(column);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR) throw bad("Formulas and error cells are not allowed in the timetable");
        return new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
    }

    private LocalDate dateValue(Cell cell, int row) {
        if (cell == null) throw bad("Row " + row + " is missing a date");
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue().toLocalDate();
            return LocalDate.parse(value(cell.getRow(), cell.getColumnIndex()));
        } catch (DateTimeParseException failure) { throw bad("Row " + row + " has an invalid date. Use YYYY-MM-DD."); }
    }

    private String timeValue(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
            return cell.getLocalDateTimeCellValue().toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
        return value(cell.getRow(), cell.getColumnIndex());
    }

    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record ScheduleRow(LocalDate date, String subject, String time) {}
}
