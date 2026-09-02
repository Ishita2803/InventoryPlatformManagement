package com.demo.carrier_service.service;

import com.demo.carrier_service.client.AuthServiceClient;
import com.demo.carrier_service.dto.CarrierResponse;
import com.demo.carrier_service.dto.OnboardCarrierRequest;
import com.demo.carrier_service.dto.WeightTierDto;
import com.demo.carrier_service.exception.CarrierNotFoundException;
import com.demo.carrier_service.exception.DuplicateCarrierCodeException;
import com.demo.carrier_service.models.Carrier;
import com.demo.carrier_service.models.WeightTier;
import com.demo.carrier_service.repository.CarrierRepository;
import com.demo.carrier_service.repository.WeightTierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CarrierService {

    private final CarrierRepository carrierRepository;
    private final WeightTierRepository weightTierRepository;
    private final AuthServiceClient authServiceClient;

    @Transactional
    public CarrierResponse onboard(OnboardCarrierRequest request) {

        if (carrierRepository.existsByCarrierCode(request.getCarrierCode())) {
            throw new DuplicateCarrierCodeException(request.getCarrierCode());
        }

        Carrier carrier = carrierRepository.save(
                new Carrier(request.getCarrierCode(), request.getCarrierName()));

        authServiceClient.createCredential(
                request.getUsername(), request.getPassword(), "CARRIER", carrier.getCarrierCode());

        log.info("Onboarded carrier {} ({})", carrier.getCarrierCode(), carrier.getCarrierName());

        return toResponse(carrier);
    }

    public List<CarrierResponse> listAll() {
        return carrierRepository.findAll().stream().map(this::toResponse).toList();
    }

    public CarrierResponse get(String carrierCode) {
        return toResponse(carrierRepository.findByCarrierCode(carrierCode)
                .orElseThrow(() -> new CarrierNotFoundException(carrierCode)));
    }

    private CarrierResponse toResponse(Carrier carrier) {
        List<WeightTierDto> tiers = weightTierRepository
                .findByCarrierCodeOrderByUpperLimitKgAsc(carrier.getCarrierCode()).stream()
                .map(t -> new WeightTierDto(t.getId(), t.getUpperLimitKg(), t.getAdditionalCost()))
                .toList();
        return new CarrierResponse(carrier.getCarrierCode(), carrier.getCarrierName(), tiers, carrier.getCreatedAt());
    }
}
