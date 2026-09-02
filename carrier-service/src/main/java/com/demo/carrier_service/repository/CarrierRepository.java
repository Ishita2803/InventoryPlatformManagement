package com.demo.carrier_service.repository;

import com.demo.carrier_service.models.Carrier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CarrierRepository extends JpaRepository<Carrier, Long> {

    Optional<Carrier> findByCarrierCode(String carrierCode);

    boolean existsByCarrierCode(String carrierCode);
}
