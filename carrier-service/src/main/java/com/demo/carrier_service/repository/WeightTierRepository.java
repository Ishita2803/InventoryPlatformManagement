package com.demo.carrier_service.repository;

import com.demo.carrier_service.models.WeightTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WeightTierRepository extends JpaRepository<WeightTier, Long> {

    List<WeightTier> findByCarrierCodeOrderByUpperLimitKgAsc(String carrierCode);

    Optional<WeightTier> findByIdAndCarrierCode(Long id, String carrierCode);
}
