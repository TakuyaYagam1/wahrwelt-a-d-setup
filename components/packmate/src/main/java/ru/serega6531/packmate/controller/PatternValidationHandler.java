package ru.serega6531.packmate.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.serega6531.packmate.exception.PatternValidationException;
import ru.serega6531.packmate.exception.RequestValidationException;

import java.util.Map;

@RestControllerAdvice
public class PatternValidationHandler {

    @ExceptionHandler(PatternValidationException.class)
    public ResponseEntity<Map<String, String>> handleInvalidPattern(PatternValidationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequest(RequestValidationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", exception.getMessage()));
    }
}
