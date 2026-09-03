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
    private final WeightTierService weightTierService;

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

    /**
     * Phase D8's actual use of {@code WeightTierService.surchargeFor} -- payment-service
     * calls this synchronously while generating an invoice. Verifying the carrier exists
     * first (rather than letting an unconfigured code silently return zero, which
     * {@code surchargeFor} does for a real carrier with no tiers) turns a typo'd
     * carrierCode into a clear 404 instead of a silently-wrong invoice.
     */
    public java.math.BigDecimal surchargeFor(String carrierCode, java.math.BigDecimal weightKg) {
        if (!carrierRepository.existsByCarrierCode(carrierCode)) {
            throw new CarrierNotFoundException(carrierCode);
        }
        return weightTierService.surchargeFor(carrierCode, weightKg);
    }

    private CarrierResponse toResponse(Carrier carrier) {
        List<WeightTierDto> tiers = weightTierRepository
                .findByCarrierCodeOrderByUpperLimitKgAsc(carrier.getCarrierCode()).stream()
                .map(t -> new WeightTierDto(t.getId(), t.getUpperLimitKg(), t.getAdditionalCost()))
                .toList();
        return new CarrierResponse(carrier.getCarrierCode(), carrier.getCarrierName(), tiers, carrier.getCreatedAt());
    }
}
