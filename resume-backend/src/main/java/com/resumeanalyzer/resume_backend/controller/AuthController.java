package com.resumeanalyzer.resume_backend.controller;

import com.resumeanalyzer.resume_backend.dto.AuthResponse;
import com.resumeanalyzer.resume_backend.dto.LoginRequest;
import com.resumeanalyzer.resume_backend.dto.RegisterRequest;
import com.resumeanalyzer.resume_backend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @RequestBody RegisterRequest request) {
        AuthResponse response = userService.register(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @RequestBody LoginRequest request) {
        AuthResponse response = userService.login(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Server is running!");
    }
}