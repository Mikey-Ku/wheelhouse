package dev.mikeyku.wheelhouse.web;

import dev.mikeyku.wheelhouse.account.NotYours;
import dev.mikeyku.wheelhouse.account.SignInRequired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Every API error is a sentence a person can read, under a status code a script can act on.
 * The messages are written for the page to show as they are, so they stay short and say what
 * to do next.
 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(SignInRequired.class)
    public ResponseEntity<Map<String, String>> signIn(SignInRequired e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(NotYours.class)
    public ResponseEntity<Map<String, String>> notYours(NotYours e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? "Something went wrong." : message));
    }
}
