package com.demo.auth_service.service;

import com.demo.auth_service.dto.CreateCredentialRequest;
import com.demo.auth_service.dto.LoginRequest;
import com.demo.auth_service.exception.DuplicateUsernameException;
import com.demo.auth_service.exception.InvalidCredentialsException;
import com.demo.auth_service.models.Credential;
import com.demo.auth_service.models.Role;
import com.demo.auth_service.repository.CredentialRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private CredentialRepository credentialRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService newAuthService() {
        JwtService jwtService = new JwtService("test-secret-at-least-32-bytes-long-0123456789", 3600);
        return new AuthService(credentialRepository, passwordEncoder, jwtService);
    }

    @Test
    void correctPasswordIssuesATokenCarryingRoleAndBusinessId() {

        AuthService service = newAuthService();

        Credential credential = new Credential(
                "vendor-demo", passwordEncoder.encode("correct-password"), Role.VENDOR, "VENDOR-1");
        when(credentialRepository.findByUsername("vendor-demo")).thenReturn(Optional.of(credential));

        LoginRequest request = new LoginRequest();
        request.setUsername("vendor-demo");
        request.setPassword("correct-password");

        var response = service.login(request);

        assertThat(response.role()).isEqualTo(Role.VENDOR);
        assertThat(response.businessId()).isEqualTo("VENDOR-1");
        assertThat(response.token()).isNotBlank();
    }

    @Test
    void wrongPasswordIsRejectedWithoutRevealingWhyBySharingOneException() {

        AuthService service = newAuthService();

        Credential credential = new Credential(
                "vendor-demo", passwordEncoder.encode("correct-password"), Role.VENDOR, "VENDOR-1");
        when(credentialRepository.findByUsername("vendor-demo")).thenReturn(Optional.of(credential));

        LoginRequest request = new LoginRequest();
        request.setUsername("vendor-demo");
        request.setPassword("wrong-password");

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void unknownUsernameFailsTheSameWayAsAWrongPassword() {

        AuthService service = newAuthService();

        when(credentialRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest();
        request.setUsername("nobody");
        request.setPassword("anything");

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void creatingACredentialForAnExistingUsernameIsRejected() {

        AuthService service = newAuthService();

        when(credentialRepository.existsByUsername("taken")).thenReturn(true);

        CreateCredentialRequest request = new CreateCredentialRequest();
        request.setUsername("taken");
        request.setPassword("whatever");
        request.setRole(Role.CUSTOMER);
        request.setBusinessId("CUSTOMER-1");

        assertThatThrownBy(() -> service.createCredential(request))
                .isInstanceOf(DuplicateUsernameException.class);

        verify(credentialRepository, never()).save(any());
    }
}
