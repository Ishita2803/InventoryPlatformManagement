package com.demo.auth_service.service;

import com.demo.auth_service.dto.CreateCredentialRequest;
import com.demo.auth_service.dto.LoginRequest;
import com.demo.auth_service.dto.LoginResponse;
import com.demo.auth_service.dto.SetPasswordRequest;
import com.demo.auth_service.dto.UpdateUserRequest;
import com.demo.auth_service.dto.UserSummaryResponse;
import com.demo.auth_service.exception.DuplicateUsernameException;
import com.demo.auth_service.exception.InvalidCredentialsException;
import com.demo.auth_service.exception.UserNotFoundException;
import com.demo.auth_service.models.Credential;
import com.demo.auth_service.repository.CredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final CredentialRepository credentialRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public LoginResponse login(LoginRequest request) {

        Credential credential = credentialRepository.findByUsername(request.getUsername())
                // Same exception for "no such user" and "wrong password" -- a 401 that
                // narrowed itself down to "no such user" would let an attacker enumerate
                // valid usernames for free.
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.getPassword(), credential.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // Same exception as a wrong password, for the same enumeration reason: a
        // disabled account should not be distinguishable from a wrong password.
        if (!credential.isEnabled()) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.issue(
                credential.getUsername(), credential.getRole(), credential.getBusinessId());

        return new LoginResponse(
                token, credential.getRole(), credential.getBusinessId(), jwtService.expirySeconds());
    }

    @Transactional
    public void createCredential(CreateCredentialRequest request) {

        if (credentialRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateUsernameException(request.getUsername());
        }

        credentialRepository.save(new Credential(
                request.getUsername(),
                passwordEncoder.encode(request.getPassword()),
                request.getRole(),
                request.getBusinessId()));
    }

    /** Admin directory. Never returns {@code passwordHash} -- mapped straight to the
     * DTO so a hash can't leak by accident later if the entity grows more fields. */
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> listUsers() {
        return credentialRepository.findAll().stream()
                .map(c -> new UserSummaryResponse(c.getUsername(), c.getRole(), c.getBusinessId(), c.isEnabled()))
                .toList();
    }

    /** Username is the path variable, not part of the request body -- it is this
     * service's identity key and is never rewritten. */
    @Transactional
    public UserSummaryResponse updateUser(String username, UpdateUserRequest request) {

        Credential credential = credentialRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException(username));

        credential.setRole(request.getRole());
        credential.setBusinessId(request.getBusinessId());
        credential.setEnabled(request.getEnabled());

        return new UserSummaryResponse(
                credential.getUsername(), credential.getRole(), credential.getBusinessId(), credential.isEnabled());
    }

    /**
     * Admin sets a new password directly, bcrypt-hashed the same as onboarding.
     * Deliberately no reset-token/email flow -- a simpler, confirmed-in-scope choice for
     * this phase; a self-service token-based reset is a stated, deferred follow-up.
     */
    @Transactional
    public void setPassword(String username, SetPasswordRequest request) {

        Credential credential = credentialRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException(username));

        credential.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
    }
}
