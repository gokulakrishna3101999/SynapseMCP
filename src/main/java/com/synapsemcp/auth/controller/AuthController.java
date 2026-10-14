package com.synapsemcp.auth.controller;

import com.synapsemcp.auth.dto.AuthRequests.SigninRequest;
import com.synapsemcp.auth.dto.AuthRequests.SignupRequest;
import com.synapsemcp.auth.dto.AuthResponse;
import com.synapsemcp.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/signin")
    public AuthResponse signin(@Valid @RequestBody SigninRequest request) {
        return authService.signin(request);
    }
}
