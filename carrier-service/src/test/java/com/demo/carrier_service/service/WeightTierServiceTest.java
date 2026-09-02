package com.demo.carrier_service.service;

import com.demo.carrier_service.dto.CreateWeightTierRequest;
import com.demo.carrier_service.models.WeightTier;
import com.demo.carrier_service.repository.WeightTierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeightTierServiceTest {

    @Mock
    private WeightTierRepository weightTierRepository;

    @InjectMocks
    private WeightTierService weightTierService;

    private List<WeightTier> threeTiers() {
        return List.of(
                new WeightTier("DTDC", new BigDecimal("1.000"), new BigDecimal("10.00")),
                new WeightTier("DTDC", new BigDecimal("5.000"), new BigDecimal("25.00")),
                new WeightTier("DTDC", new BigDecimal("10.000"), new BigDecimal("50.00")));
    }

    @Test
    void weightWithinTheFirstTierUsesTheFirstTiersSurcharge() {

        when(weightTierRepository.findByCarrierCodeOrderByUpperLimitKgAsc("DTDC")).thenReturn(threeTiers());

        assertThat(weightTierService.surchargeFor("DTDC", new BigDecimal("0.500")))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void weightExactlyAtATierBoundaryUsesThatTier() {

        when(weightTierRepository.findByCarrierCodeOrderByUpperLimitKgAsc("DTDC")).thenReturn(threeTiers());

        assertThat(weightTierService.surchargeFor("DTDC", new BigDecimal("5.000")))
                .isEqualByComparingTo("25.00");
    }

    @Test
    void weightHeavierThanEveryTierUsesTheHeaviestTierAsACeiling() {

        when(weightTierRepository.findByCarrierCodeOrderByUpperLimitKgAsc("DTDC")).thenReturn(threeTiers());

        assertThat(weightTierService.surchargeFor("DTDC", new BigDecimal("999.000")))
                .isEqualByComparingTo("50.00");
    }

    @Test
    void aCarrierWithNoTiersAtAllChargesNoSurcharge() {

        when(weightTierRepository.findByCarrierCodeOrderByUpperLimitKgAsc("UNCONFIGURED")).thenReturn(List.of());

        assertThat(weightTierService.surchargeFor("UNCONFIGURED", new BigDecimal("2.000")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
