package com.edura.schoolos.holiday;

import com.edura.schoolos.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/** School-wide holiday calendar. Images are stored with the record so the uploaded notice remains available. */
@RestController
@RequestMapping("/api/v1/holidays")
public class HolidayCalendarController {
    private final JdbcTemplate jdbc;
    public HolidayCalendarController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER','STUDENT','PARENT')")
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select id, event_name, holiday_date, image_data, created_at from holiday_calendars where tenant_id = ? order by holiday_date", TenantContext.require());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@Valid @RequestBody NewHoliday request, @AuthenticationPrincipal Jwt jwt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into holiday_calendars(id, tenant_id, event_name, holiday_date, image_data, created_by) values (?, ?, ?, ?, ?, ?)",
            id, TenantContext.require(), request.eventName().trim(), request.holidayDate(), blankToNull(request.imageData()), jwt.getSubject());
        return Map.of("id", id, "eventName", request.eventName().trim(), "holidayDate", request.holidayDate(), "imageData", request.imageData() == null ? "" : request.imageData());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) {
        jdbc.update("delete from holiday_calendars where id = ? and tenant_id = ?", id, TenantContext.require());
    }

    /** Imports a holiday list whose column headings are Event and Date. Supports CSV, Excel and DOCX tables. */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> importList(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) throws Exception {
        if (file.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a holiday list file");
        var rows = readRows(file);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "No Event and Date rows were found in this file");
        var tenant = TenantContext.require();
        List<Map<String, Object>> saved = new ArrayList<>();
        for (HolidayRow row : rows) {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into holiday_calendars(id, tenant_id, event_name, holiday_date, created_by) values (?, ?, ?, ?, ?) on conflict (tenant_id, holiday_date, event_name) do nothing", id, tenant, row.event(), row.date(), jwt.getSubject());
            saved.add(Map.of("id", id, "eventName", row.event(), "holidayDate", row.date()));
        }
        return saved;
    }

    private List<HolidayRow> readRows(MultipartFile file) throws Exception {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (name.endsWith(".csv")) return csvRows(new String(file.getBytes(), StandardCharsets.UTF_8));
        if (name.endsWith(".xlsx")) return excelRows(file.getBytes());
        if (name.endsWith(".docx")) return docxRows(file.getBytes());
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a CSV, XLSX, or DOCX holiday list with Event and Date columns");
    }
    private List<HolidayRow> csvRows(String content) {
        List<String[]> cells = content.lines().filter(line -> !line.isBlank()).map(line -> line.split(",", -1)).toList();
        return indexedRows(cells);
    }
    private List<HolidayRow> excelRows(byte[] bytes) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(); List<String[]> cells = new ArrayList<>();
            for (Row row : workbook.getSheetAt(0)) { String[] current = new String[row.getLastCellNum() < 0 ? 0 : row.getLastCellNum()]; for (int i = 0; i < current.length; i++) current[i] = formatter.formatCellValue(row.getCell(i)); cells.add(current); }
            return indexedRows(cells);
        }
    }
    private List<HolidayRow> docxRows(byte[] bytes) throws Exception {
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String[]> cells = new ArrayList<>(); document.getTables().forEach(table -> table.getRows().forEach(row -> cells.add(row.getTableCells().stream().map(cell -> cell.getText()).toArray(String[]::new))));
            return indexedRows(cells);
        }
    }
    private List<HolidayRow> indexedRows(List<String[]> cells) {
        if (cells.isEmpty()) return List.of(); String[] headers = cells.get(0); int eventIndex = indexOf(headers, "event"); int dateIndex = indexOf(headers, "date");
        if (eventIndex < 0 || dateIndex < 0) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "The first row must contain Event and Date column names");
        List<HolidayRow> result = new ArrayList<>(); for (String[] row : cells.subList(1, cells.size())) if (row.length > Math.max(eventIndex, dateIndex) && !row[eventIndex].isBlank() && !row[dateIndex].isBlank()) result.add(new HolidayRow(row[eventIndex].trim(), parseDate(row[dateIndex].trim()))); return result;
    }
    private int indexOf(String[] headers, String expected) { for (int i = 0; i < headers.length; i++) if (expected.equalsIgnoreCase(headers[i].trim())) return i; return -1; }
    private LocalDate parseDate(String value) {
        List<DateTimeFormatter> formats = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/uuuu"), DateTimeFormatter.ofPattern("d/M/uuuu"),
            new DateTimeFormatterBuilder().appendPattern("M/d/").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(),
            new DateTimeFormatterBuilder().appendPattern("d/M/").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(),
            DateTimeFormatter.ofPattern("d-MMM-uuuu"), DateTimeFormatter.ofPattern("d MMM uuuu"));
        for (DateTimeFormatter format : formats) try { return LocalDate.parse(value, format); } catch (java.time.format.DateTimeParseException ignored) { }
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date '" + value + "'. Use YYYY-MM-DD or an Excel date value");
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    public record NewHoliday(@NotBlank String eventName, @NotNull LocalDate holidayDate, String imageData) {}
    private record HolidayRow(String event, LocalDate date) {}
}
