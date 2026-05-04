package com.resumeanalyzer.resume_backend.service;

import com.resumeanalyzer.resume_backend.dto.AuthResponse;
import com.resumeanalyzer.resume_backend.dto.LoginRequest;
import com.resumeanalyzer.resume_backend.dto.RegisterRequest;
import com.resumeanalyzer.resume_backend.model.User;
import com.resumeanalyzer.resume_backend.repository.UserRepository;
import com.resumeanalyzer.resume_backend.security.JwtUtil;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {
        User user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() ->
                        new UsernameNotFoundException("User not found: " + email));

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                new ArrayList<>()
        );
    }

    public AuthResponse register(RegisterRequest request) {
        String name = request.getName() == null ? "" : request.getName().trim();
        String email = normalizeEmail(request.getEmail());
        String password = request.getPassword() == null ? "" : request.getPassword();

        if (name.isBlank()) {
            throw new RuntimeException("Name is required");
        }

        if (email.isBlank()) {
            throw new RuntimeException("Email is required");
        }

        if (password.isBlank()) {
            throw new RuntimeException("Password is required");
        }

        if (userRepository.existsByEmail(email)) {
            throw new RuntimeException("Email already registered. Please login instead.");
        }

        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("USER");

        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(token, user.getEmail(), user.getName());
    }

    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        String rawPassword = request.getPassword() == null ? "" : request.getPassword();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new RuntimeException("Invalid email or password"));

        String savedPassword = user.getPassword();

        boolean validPassword = isBCryptHash(savedPassword)
                ? passwordEncoder.matches(rawPassword, savedPassword)
                : rawPassword.equals(savedPassword);

        if (!validPassword) {
            throw new RuntimeException("Invalid email or password");
        }

        if (!isBCryptHash(savedPassword)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            userRepository.save(user);
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(token, user.getEmail(), user.getName());
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private boolean isBCryptHash(String password) {
        return password != null && password.matches("^\\$2[aby]\\$.{56}$");
    }
}
