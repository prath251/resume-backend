package com.resumeanalyzer.resume_backend.controller;

import com.resumeanalyzer.resume_backend.dto.AuthResponse;
import com.resumeanalyzer.resume_backend.dto.LoginRequest;
import com.resumeanalyzer.resume_backend.dto.RegisterRequest;
import com.resumeanalyzer.resume_backend.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        try {
            AuthResponse response = userService.register(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "Registration failed" : e.getMessage();
            HttpStatus status = message.toLowerCase().contains("already")
                    ? HttpStatus.CONFLICT
                    : HttpStatus.BAD_REQUEST;

            return ResponseEntity.status(status).body(Map.of("error", message));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            AuthResponse response = userService.login(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid email or password"));
        }
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Server is running!");
    }
}
