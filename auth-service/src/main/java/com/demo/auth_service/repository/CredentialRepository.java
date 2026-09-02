package com.demo.auth_service.repository;

import com.demo.auth_service.models.Credential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CredentialRepository extends JpaRepository<Credential, Long> {

    Optional<Credential> findByUsername(String username);

    boolean existsByUsername(String username);
}
