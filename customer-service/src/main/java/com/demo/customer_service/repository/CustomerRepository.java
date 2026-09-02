package com.demo.customer_service.repository;

import com.demo.customer_service.models.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByCustomerNo(String customerNo);
}
