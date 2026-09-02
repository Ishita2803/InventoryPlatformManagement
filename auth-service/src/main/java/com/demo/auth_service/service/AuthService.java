package com.demo.auth_service.service;

import com.demo.auth_service.dto.CreateCredentialRequest;
import com.demo.auth_service.dto.LoginRequest;
import com.demo.auth_service.dto.LoginResponse;
import com.demo.auth_service.exception.DuplicateUsernameException;
import com.demo.auth_service.exception.InvalidCredentialsException;
import com.demo.auth_service.models.Credential;
import com.demo.auth_service.repository.CredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
