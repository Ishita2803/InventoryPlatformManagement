package com.demo.carrier_service.service;

import com.demo.carrier_service.dto.CreateWeightTierRequest;
import com.demo.carrier_service.dto.WeightTierDto;
import com.demo.carrier_service.exception.WeightTierNotFoundException;
import com.demo.carrier_service.models.WeightTier;
import com.demo.carrier_service.repository.WeightTierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Same ownership shape as vendor-service's ProductService: every mutation scoped to the
 * calling carrier's own carrierCode, taken from the gateway-forwarded header. */
@Service
@RequiredArgsConstructor
public class WeightTierService {

    private final WeightTierRepository weightTierRepository;

    @Transactional
    public WeightTierDto add(String carrierCode, CreateWeightTierRequest request) {
        WeightTier tier = weightTierRepository.save(
                new WeightTier(carrierCode, request.getUpperLimitKg(), request.getAdditionalCost()));
        return toDto(tier);
    }

    public List<WeightTierDto> list(String carrierCode) {
        return weightTierRepository.findByCarrierCodeOrderByUpperLimitKgAsc(carrierCode).stream()
                .map(this::toDto).toList();
    }

    @Transactional
    public void delete(String carrierCode, Long tierId) {
        WeightTier tier = weightTierRepository.findByIdAndCarrierCode(tierId, carrierCode)
                .orElseThrow(() -> new WeightTierNotFoundException(tierId));
        weightTierRepository.delete(tier);
    }

    /**
     * The lookup Phase D8's invoicing will actually call: the first tier (ascending by
     * upper limit) the given weight does not exceed, or the heaviest tier as a ceiling if
     * the weight exceeds every tier defined. Returns {@link BigDecimal#ZERO} if the
     * carrier has no tiers at all -- a carrier onboarded but never configured charges no
     * weight surcharge, which is a safe default rather than a broken invoice.
     */
    public BigDecimal surchargeFor(String carrierCode, BigDecimal weightKg) {
        List<WeightTier> tiers = weightTierRepository.findByCarrierCodeOrderByUpperLimitKgAsc(carrierCode);

        if (tiers.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return tiers.stream()
                .filter(t -> weightKg.compareTo(t.getUpperLimitKg()) <= 0)
                .findFirst()
                .orElse(tiers.get(tiers.size() - 1))
                .getAdditionalCost();
    }

    private WeightTierDto toDto(WeightTier tier) {
        return new WeightTierDto(tier.getId(), tier.getUpperLimitKg(), tier.getAdditionalCost());
    }
}
