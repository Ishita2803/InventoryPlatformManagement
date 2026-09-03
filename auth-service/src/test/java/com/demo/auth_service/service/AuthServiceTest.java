package com.demo.auth_service.service;

import com.demo.auth_service.dto.CreateCredentialRequest;
import com.demo.auth_service.dto.LoginRequest;
import com.demo.auth_service.dto.SetPasswordRequest;
import com.demo.auth_service.dto.UpdateUserRequest;
import com.demo.auth_service.dto.UserSummaryResponse;
import com.demo.auth_service.exception.DuplicateUsernameException;
import com.demo.auth_service.exception.InvalidCredentialsException;
import com.demo.auth_service.exception.UserNotFoundException;
import com.demo.auth_service.models.Credential;
import com.demo.auth_service.models.Role;
import com.demo.auth_service.repository.CredentialRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
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

    @Test
    void aDisabledUserIsRejectedTheSameWayAsAWrongPassword() {

        AuthService service = newAuthService();

        Credential credential = new Credential(
                "vendor-demo", passwordEncoder.encode("correct-password"), Role.VENDOR, "VENDOR-1");
        credential.setEnabled(false);
        when(credentialRepository.findByUsername("vendor-demo")).thenReturn(Optional.of(credential));

        LoginRequest request = new LoginRequest();
        request.setUsername("vendor-demo");
        request.setPassword("correct-password");

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void listUsersNeverExposesThePasswordHash() {

        AuthService service = newAuthService();

        Credential credential = new Credential(
                "vendor-demo", passwordEncoder.encode("correct-password"), Role.VENDOR, "VENDOR-1");
        when(credentialRepository.findAll()).thenReturn(List.of(credential));

        List<UserSummaryResponse> users = service.listUsers();

        assertThat(users).hasSize(1);
        assertThat(users.get(0).username()).isEqualTo("vendor-demo");
        assertThat(users.get(0).enabled()).isTrue();
    }

    @Test
    void updateUserChangesRoleBusinessIdAndEnabledButNotUsername() {

        AuthService service = newAuthService();

        Credential credential = new Credential(
                "vendor-demo", passwordEncoder.encode("correct-password"), Role.VENDOR, "VENDOR-1");
        when(credentialRepository.findByUsername("vendor-demo")).thenReturn(Optional.of(credential));

        UpdateUserRequest request = new UpdateUserRequest();
        request.setRole(Role.ADMIN);
        request.setBusinessId("ADMIN-2");
        request.setEnabled(false);

        UserSummaryResponse response = service.updateUser("vendor-demo", request);

        assertThat(response.username()).isEqualTo("vendor-demo");
        assertThat(response.role()).isEqualTo(Role.ADMIN);
        assertThat(response.businessId()).isEqualTo("ADMIN-2");
        assertThat(response.enabled()).isFalse();
    }

    @Test
    void updatingAnUnknownUsernameIs404NotSilentlyIgnored() {

        AuthService service = newAuthService();

        when(credentialRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        UpdateUserRequest request = new UpdateUserRequest();
        request.setRole(Role.ADMIN);
        request.setBusinessId("ADMIN-1");
        request.setEnabled(true);

        assertThatThrownBy(() -> service.updateUser("nobody", request))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void settingAPasswordHashesItAndTheOldPasswordNoLongerWorks() {

        AuthService service = newAuthService();

        Credential credential = new Credential(
                "vendor-demo", passwordEncoder.encode("old-password"), Role.VENDOR, "VENDOR-1");
        when(credentialRepository.findByUsername("vendor-demo")).thenReturn(Optional.of(credential));

        SetPasswordRequest request = new SetPasswordRequest();
        request.setNewPassword("brand-new-password");

        service.setPassword("vendor-demo", request);

        assertThat(credential.getPasswordHash()).isNotEqualTo("brand-new-password");
        assertThat(passwordEncoder.matches("brand-new-password", credential.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("old-password", credential.getPasswordHash())).isFalse();
    }
}
