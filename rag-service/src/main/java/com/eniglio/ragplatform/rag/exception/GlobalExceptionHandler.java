package com.eniglio.ragplatform.rag.exception;

import com.eniglio.ragplatform.common.web.ErrorResponse;
import com.eniglio.ragplatform.common.web.GlobalExceptionHandlerSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler extends GlobalExceptionHandlerSupport {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Invalid request");
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(UnsupportedImageTypeException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedImageType(UnsupportedImageTypeException ex, HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidImageAttachmentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidImageAttachment(InvalidImageAttachmentException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Attached image exceeds the maximum allowed size", request);
    }
}
