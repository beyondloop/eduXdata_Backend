package com.edura.schoolos.teacher;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SyllabusPdfExtractorTest {
    static MockMultipartFile pdf(String text) throws Exception {
        try (var doc = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            if (!text.isEmpty()) try (var content = new PDPageContentStream(doc, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700); content.showText(text); content.endText();
            }
            doc.save(bytes);
            return new MockMultipartFile("file", "syllabus.pdf", "application/pdf", bytes.toByteArray());
        }
    }
    @Test void extractsOnlyAssignedSubjectsAndRecognizesAliases() throws Exception {
        var result = SyllabusPdfExtractor.extract(pdf("TERM 1: MATHS, ENGLISH, EVS, History"), List.of("Mathematics", "English", "Environmental Studies", "Physics"));
        assertEquals(List.of("Mathematics", "English", "Environmental Studies"), result.subjects());
    }
    @Test void matchesWholeWordsAndDoesNotInventSubjects() throws Exception {
        var result = SyllabusPdfExtractor.extract(pdf("Mathematical thinking and craftsmanship"), List.of("Math", "Art", "English"));
        assertTrue(result.subjects().isEmpty());
        assertTrue(result.message().contains("manually"));
    }
    @Test void blankOrImageOnlyDocumentHasActionableFallback() throws Exception {
        var result = SyllabusPdfExtractor.extract(pdf(""), List.of("Mathematics"));
        assertTrue(result.subjects().isEmpty()); assertTrue(result.message().contains("OCR"));
    }
    @Test void compoundSubjectDoesNotSelectTheGeneralSubject() throws Exception {
        var result = SyllabusPdfExtractor.extract(pdf("Computer Science and Social Science"), List.of("Science", "Computer Science", "Social Studies"));
        assertEquals(List.of("Computer Science", "Social Studies"), result.subjects());
    }
    @Test void rejectsInvalidAndOversizeFiles() {
        assertThrows(ResponseStatusException.class, () -> SyllabusPdfExtractor.extract(new MockMultipartFile("file", "fake.pdf", "application/pdf", "not a PDF".getBytes()), List.of("Math")));
        assertThrows(ResponseStatusException.class, () -> SyllabusPdfExtractor.extract(new MockMultipartFile("file", new byte[5 * 1024 * 1024 + 1]), List.of("Math")));
    }
}
