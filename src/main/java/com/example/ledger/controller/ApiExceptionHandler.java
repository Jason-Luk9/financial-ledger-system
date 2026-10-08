package com.example.ledger.controller;

import com.example.ledger.dto.ApiModels;
import com.example.ledger.exception.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<ApiModels.ErrorView> ledger(LedgerException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ApiModels.ErrorView(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiModels.ErrorView> invalid(Exception ex) {
        String message = "Invalid request body, header, or parameter";
        if (ex instanceof MethodArgumentNotValidException validation) {
            message = validation.getBindingResult().getFieldErrors().stream()
                    .map(e -> e.getField() + ": " + e.getDefaultMessage()).sorted().collect(java.util.stream.Collectors.joining("; "));
        }
        return ResponseEntity.badRequest().body(new ApiModels.ErrorView("INVALID_REQUEST", message));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiModels.ErrorView> database(DataAccessException ex) {
        log.error("Database operation failed; transaction rolled back", ex);
        return ResponseEntity.status(503).body(new ApiModels.ErrorView("DATABASE_UNAVAILABLE", "Database operation failed; retry with the same idempotency key"));
    }
}
