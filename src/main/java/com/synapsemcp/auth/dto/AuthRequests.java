package com.synapsemcp.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthRequests {

    private AuthRequests() {
    }

    /** Password max is 72 because BCrypt ignores/rejects anything beyond 72 bytes. */
    public record SignupRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 255) String fullName) {
    }

    public record SigninRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }
}
