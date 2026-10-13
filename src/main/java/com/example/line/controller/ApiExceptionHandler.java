package com.example.line.controller;

import com.example.line.service.LineSendException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record ApiError(String code, Integer upstreamStatus) {}
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", null));
    }
    @ExceptionHandler(LineSendException.class)
    public ResponseEntity<ApiError> lineError(LineSendException exception) {
        int status = exception.getReason() == LineSendException.Reason.TRANSIENT_FAILURE
                || exception.getReason() == LineSendException.Reason.INTERRUPTED ? 503 : 502;
        return ResponseEntity.status(status)
                .body(new ApiError("LINE_" + exception.getReason(), exception.getUpstreamStatus()));
    }
}
