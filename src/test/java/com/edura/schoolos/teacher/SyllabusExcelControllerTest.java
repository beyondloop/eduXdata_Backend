package com.edura.schoolos.teacher;

import com.edura.schoolos.security.TenantContext;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.time.LocalDate;
import org.apache.poi.ss.util.CellReference;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SyllabusExcelControllerTest {
    JdbcTemplate jdbc;
    SyllabusExcelController controller;
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class); controller=new SyllabusExcelController(jdbc); TenantContext.set(UUID.randomUUID());
        when(jdbc.queryForList(anyString(),eq(String.class),any(Object[].class))).thenReturn(List.of("TH1"));
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(false);
        when(jdbc.queryForObject(anyString(),eq(String.class),any(Object[].class))).thenReturn("Jane Smith");
    }
    @AfterEach void cleanup() { TenantContext.clear(); }
    byte[] workbook(String start,String due,boolean duplicate,boolean formula) throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(controller.template().getBody())); var out=new ByteArrayOutputStream()) {
            String[] values={"2026-27","A","MATH","Mathematics","Algebra","Equations","Solve equations","EMP1",start,due,"HIGH","Jane Smith"};
            for(int r=1;r<=(duplicate?2:1);r++) {
                var row=book.getSheet("Class 1").createRow(r);
                for(int c=0;c<values.length;c++) row.createCell(c).setCellValue(values[c]);
                if(formula)row.getCell(5).setCellFormula("1+1");
            }
            book.write(out);return out.toByteArray();
        }
    }
    @Test void termColumnAcceptsDropdownValuesAndRejectsInvalidTerm() throws Exception {
        byte[] original=workbook("2026-04-01","2026-06-01",false,false);
        for(String term:List.of("TERM_1","TERM_2","FINAL","INVALID")) {
            try(var book=new XSSFWorkbook(new ByteArrayInputStream(original));var out=new ByteArrayOutputStream()) {
                book.getSheet("Class 1").getRow(1).createCell(12).setCellValue(term);
                book.write(out);
                var result=controller.validate(out.toByteArray());
                if(term.equals("INVALID")) assertFalse(result.errors().isEmpty());
                else {assertTrue(result.errors().isEmpty(),result.errors().toString());assertEquals(term,result.rows().get(0).term());}
            }
        }
    }
    @Test void previousTemplateWithoutTermRemainsSupported() throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(workbook("2026-04-01","2026-06-01",false,false)));var out=new ByteArrayOutputStream()) {
            for(int n=1;n<=12;n++){var header=book.getSheet("Class "+n).getRow(0);header.removeCell(header.getCell(12));}
            book.write(out);
            var result=controller.validate(out.toByteArray());
            assertTrue(result.errors().isEmpty(),result.errors().toString());
            assertEquals("",result.rows().get(0).term());
        }
    }
    @Test void templateHasExactSheetsAndHeaders() throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(controller.template().getBody()))) {
            assertEquals(13,book.getNumberOfSheets()); assertNotNull(book.getSheet("Instructions"));
            for(int n=1;n<=12;n++) for(int c=0;c<SyllabusExcelController.HEADERS.length;c++) assertEquals(SyllabusExcelController.HEADERS[c],book.getSheet("Class "+n).getRow(0).getCell(c).getStringCellValue());
        }
    }
    @Test void academicYearChangesAtAprilBoundary() {
        assertEquals("2025-26", SyllabusExcelController.currentAcademicYear(LocalDate.of(2026, 3, 31)));
        assertEquals("2026-27", SyllabusExcelController.currentAcademicYear(LocalDate.of(2026, 4, 1)));
        assertEquals("2026-27", SyllabusExcelController.currentAcademicYear(LocalDate.of(2026, 10, 6)));
        assertEquals("2099-00", SyllabusExcelController.currentAcademicYear(LocalDate.of(2099, 4, 1)));
    }
    @Test void dropdownsUseClassWorkloadsAndPreserveEmptyTemplate() throws Exception {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
            Map.of("class_name", "1", "section_name", "A", "subject", "Mathematics", "teacher_id", "EMP1", "display_name", "Jane Smith"),
            Map.of("class_name", "1", "section_name", "A", "subject", "Mathematics", "teacher_id", "EMP1", "display_name", "Jane Smith"),
            Map.of("class_name", "2", "section_name", "B", "subject", "English", "teacher_id", "EMP2", "display_name", "John Doe")));
        byte[] bytes = controller.template().getBody();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int n = 1; n <= 12; n++) {
                var validations = book.getSheet("Class " + n).getDataValidations();
                assertEquals(9, validations.size());
                assertEquals(List.of(0, 1, 3, 7, 12, 10, 11, 8, 9), validations.stream()
                    .map(v -> v.getRegions().getCellRangeAddress(0).getFirstColumn()).toList());
                for (var validation : validations) {
                    assertEquals(1, validation.getRegions().getCellRangeAddress(0).getFirstRow());
                    assertEquals(2000, validation.getRegions().getCellRangeAddress(0).getLastRow());
                    assertTrue(validation.getShowErrorBox());
                    if (validation.getValidationConstraint().getValidationType() == org.apache.poi.ss.usermodel.DataValidationConstraint.ValidationType.LIST)
                        assertNotNull(book.getName(validation.getValidationConstraint().getFormula1()));
                }
            }
            assertEquals("Mathematics", firstOption(book, "Subjects_Class_1"));
            assertEquals("EMP1", firstOption(book, "Employees_Class_1"));
            assertEquals("Jane Smith", firstOption(book, "TeacherNames_Class_1"));
            assertEquals("English", firstOption(book, "Subjects_Class_2"));
            assertEquals("EMP2", firstOption(book, "Employees_Class_2"));
            assertEquals("", firstOption(book, "Employees_Class_3"));
            assertEquals(SyllabusExcelController.currentAcademicYear(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))),
                firstOption(book, "Year_Class_1"));
        }
        assertEquals(List.of("Workbook has no topics"), controller.validate(bytes).errors());
    }
    private String firstOption(XSSFWorkbook book, String name) {
        String formula = book.getName(name).getRefersToFormula();
        var ref = new CellReference(formula.substring(formula.indexOf('!') + 1).split(":")[0]);
        assertTrue(book.getSheet("Instructions").isColumnHidden(ref.getCol()));
        return book.getSheet("Instructions").getRow(ref.getRow()).getCell(ref.getCol()).getStringCellValue();
    }
    @Test void validatesRowsAndDetectsDuplicates() throws Exception {
        var result=controller.validate(workbook("2026-04-01","2026-04-30",true,false));
        assertEquals(1,result.rows().size());assertEquals(1,result.errors().size());assertTrue(result.errors().get(0).contains("Duplicate"));
    }
    @Test void rejectsInvalidDatesAndFormulas() throws Exception {
        assertFalse(controller.validate(workbook("2026-05-01","2026-04-30",false,false)).errors().isEmpty());
        assertThrows(ResponseStatusException.class,()->controller.validate(workbook("2026-04-01","2026-04-30",false,true)));
    }
    @Test void rejectsUnknownTeacherAndEmptyWorkbook() throws Exception {
        when(jdbc.queryForList(anyString(),eq(String.class),any(Object[].class))).thenReturn(List.of());
        assertTrue(controller.validate(workbook("2026-04-01","2026-04-30",false,false)).errors().get(0).contains("active assignment"));
        assertFalse(controller.validate(controller.template().getBody()).errors().isEmpty());
    }
    @Test void acceptsCalendarDatesAndChecksTeacherName() throws Exception {
        byte[] data;
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook("2026-04-01", "2026-04-30", false, false)));
             var out = new ByteArrayOutputStream()) {
            var sheet = book.getSheet("Class 1");
            var row = sheet.getRow(1);
            for (int c : List.of(8, 9)) {
                row.getCell(c).setCellValue(LocalDate.of(2026, 4, c == 8 ? 1 : 30));
                row.getCell(c).setCellStyle(sheet.getRow(2).getCell(c).getCellStyle());
                assertEquals("yyyy-mm-dd", row.getCell(c).getCellStyle().getDataFormatString());
            }
            book.write(out); data = out.toByteArray();
        }
        var valid = controller.validate(data);
        assertTrue(valid.errors().isEmpty(), valid.errors().toString());
        assertEquals(LocalDate.of(2026, 4, 30), valid.rows().get(0).dueDate());
        assertEquals("Jane Smith", valid.rows().get(0).teacherName());
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn("Another Teacher");
        assertTrue(controller.validate(data).errors().get(0).contains("TeacherName does not match"));
    }
    @Test void supportsPreviouslyDownloadedTemplateWithoutTeacherName() throws Exception {
        byte[] data;
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook("2026-04-01", "2026-04-30", false, false)));
             var out = new ByteArrayOutputStream()) {
            for (int n = 1; n <= 12; n++) {
                var sheet = book.getSheet("Class " + n);
                sheet.getRow(0).removeCell(sheet.getRow(0).getCell(11));
                sheet.getRow(0).removeCell(sheet.getRow(0).getCell(12));
            }
            var row = book.getSheet("Class 1").getRow(1);
            row.removeCell(row.getCell(11));
            book.write(out); data = out.toByteArray();
        }
        var result = controller.validate(data);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals("Jane Smith", result.rows().get(0).teacherName());
    }
}
