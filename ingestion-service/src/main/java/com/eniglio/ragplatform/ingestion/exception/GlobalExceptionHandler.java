package com.eniglio.ragplatform.ingestion.exception;

import com.eniglio.ragplatform.common.web.ErrorResponse;
import com.eniglio.ragplatform.common.web.GlobalExceptionHandlerSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.UncheckedIOException;

@RestControllerAdvice
public class GlobalExceptionHandler extends GlobalExceptionHandlerSupport {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // docs/ROADMAP.md item #24
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Invalid request");
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(InvalidSharingRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSharingRequest(InvalidSharingRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(NotDocumentOwnerException.class)
    public ResponseEntity<ErrorResponse> handleNotDocumentOwner(NotDocumentOwnerException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotFound(DocumentNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(UnsupportedDocumentTypeException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedType(UnsupportedDocumentTypeException ex, HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidUploadException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUpload(InvalidUploadException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file exceeds the maximum allowed size", request);
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<ErrorResponse> handleIoError(UncheckedIOException ex, HttpServletRequest request) {
        log.error("I/O error while processing upload", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read the uploaded file", request);
    }
}
