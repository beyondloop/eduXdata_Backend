package com.edura.schoolos.teacher;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Extracts selectable subject names; it never publishes or invents topic deadlines. */
public final class SyllabusPdfExtractor {
    private SyllabusPdfExtractor() {}
    private static final List<List<String>> ALIASES = List.of(
        List.of("mathematics", "maths", "math"), List.of("english", "english language"),
        List.of("science", "general science"), List.of("social studies", "social science", "sst"),
        List.of("computer science", "computer studies", "computers"),
        List.of("environmental studies", "environmental science", "evs"),
        List.of("physical education", "p e"), List.of("general knowledge", "gk"));

    public static Result extract(MultipartFile file, List<String> assignedSubjects) {
        if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024)
            throw bad("Choose a non-empty PDF of 5 MB or smaller");
        try (var document = Loader.loadPDF(file.getBytes())) {
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent())
                throw bad("Upload an unlocked PDF that permits text extraction");
            if (document.getNumberOfPages() > 100) throw bad("Use a syllabus PDF with no more than 100 pages");
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            if (text.isBlank()) return new Result(List.of(), "No readable text found. Scanned PDFs need OCR first; select subjects manually below.");
            String normalized = normalize(text);
            List<String> matched = assignedSubjects.stream().filter(subject -> matches(normalized, subject, assignedSubjects)).toList();
            return new Result(matched, matched.isEmpty()
                ? "No assigned class subjects matched the PDF text. Select the subjects manually or check their names in Teacher Workload."
                : "Subjects detected from PDF text. Review the selection before publishing; monthly topics and deadlines are entered below.");
        } catch (IOException | IllegalArgumentException e) {
            throw bad("Unable to read this PDF. Upload a valid, unlocked PDF or select subjects manually.");
        }
    }

    static List<String> names(String subject) {
        String name = normalize(subject);
        return ALIASES.stream().filter(group -> group.contains(name)).findFirst().orElse(List.of(name));
    }
    private static boolean matches(String text, String subject, List<String> assigned) {
        // Do not infer Science just because Computer Science or Social Science appears.
        for (String name : names(subject)) {
            String candidateText = text;
            for (String other : assigned) {
                if (other.equals(subject)) continue;
                for (String longer : names(other)) {
                    if (longer.length() > name.length() && contains(longer, name))
                        candidateText = candidateText.replaceAll("(?<![\\p{L}\\p{N}])" + Pattern.quote(longer) + "(?![\\p{L}\\p{N}])", " ");
                }
            }
            if (contains(candidateText, name)) return true;
        }
        return false;
    }
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }
    private static boolean contains(String text, String name) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])").matcher(text).find();
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    public record Result(List<String> subjects, String message) {}
}
