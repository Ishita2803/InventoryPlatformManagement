package com.demo.customer_service.repository;

import com.demo.customer_service.models.EndUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EndUserRepository extends JpaRepository<EndUser, Long> {

    List<EndUser> findByCustomerNo(String customerNo);

    Optional<EndUser> findByIdAndCustomerNo(Long id, String customerNo);

    Optional<EndUser> findByEndUserId(String endUserId);
}
