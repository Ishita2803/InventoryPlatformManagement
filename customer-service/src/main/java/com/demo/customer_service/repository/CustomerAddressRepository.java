package com.demo.customer_service.repository;

import com.demo.customer_service.models.CustomerAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    List<CustomerAddress> findByCustomerNo(String customerNo);

    Optional<CustomerAddress> findByIdAndCustomerNo(Long id, String customerNo);
}
