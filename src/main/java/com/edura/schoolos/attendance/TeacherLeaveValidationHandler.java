package com.edura.schoolos.attendance;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TeacherLeaveController.class)
public class TeacherLeaveValidationHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidApplication(MethodArgumentNotValidException failure) {
        String message = failure.getBindingResult().getFieldErrors().stream()
            .map(error -> switch (error.getField()) {
                case "leaveType" -> "Choose Sick, Casual or Emergency leave.";
                case "fromDate" -> "Choose a valid start date.";
                case "toDate" -> "Choose a valid end date.";
                case "reason" -> "Enter a reason between 1 and 1000 characters.";
                default -> "Check " + error.getField() + ".";
            }).distinct().collect(Collectors.joining(" "));
        return Map.of("message", message.isBlank() ? "Check the leave application details." : message);
    }
}
