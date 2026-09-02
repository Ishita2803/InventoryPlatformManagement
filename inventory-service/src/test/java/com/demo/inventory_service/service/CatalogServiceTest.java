package com.demo.inventory_service.service;

import com.demo.inventory_service.client.VendorServiceClient;
import com.demo.inventory_service.dto.SetSalePriceRequest;
import com.demo.inventory_service.models.CatalogItem;
import com.demo.inventory_service.repository.CatalogItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private CatalogItemRepository catalogItemRepository;

    @Mock
    private VendorServiceClient vendorServiceClient;

    @InjectMocks
    private CatalogService catalogService;

    @Test
    void settingAPriceForANewSkuCreatesACatalogItemWithVendorDataDenormalized() {

        when(vendorServiceClient.getProductBySku("SKU-1"))
                .thenReturn(new VendorServiceClient.VendorProduct("VENDOR-1", new BigDecimal("1.500")));
        when(catalogItemRepository.findBySkuNumber("SKU-1")).thenReturn(Optional.empty());
        when(catalogItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SetSalePriceRequest request = new SetSalePriceRequest();
        request.setSkuNumber("SKU-1");
        request.setSalePrice(new BigDecimal("70.00"));

        var response = catalogService.setSalePrice(request);

        assertThat(response.vendorId()).isEqualTo("VENDOR-1");
        assertThat(response.unitWeight()).isEqualByComparingTo("1.500");
        assertThat(response.salePrice()).isEqualByComparingTo("70.00");
    }

    @Test
    void settingAPriceForAnExistingSkuUpdatesItInPlaceRatherThanCreatingASecondRow() {

        CatalogItem existing = new CatalogItem("SKU-1", "VENDOR-1", new BigDecimal("1.500"), new BigDecimal("60.00"));

        when(vendorServiceClient.getProductBySku("SKU-1"))
                .thenReturn(new VendorServiceClient.VendorProduct("VENDOR-1", new BigDecimal("1.500")));
        when(catalogItemRepository.findBySkuNumber("SKU-1")).thenReturn(Optional.of(existing));
        when(catalogItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SetSalePriceRequest request = new SetSalePriceRequest();
        request.setSkuNumber("SKU-1");
        request.setSalePrice(new BigDecimal("75.00"));

        var response = catalogService.setSalePrice(request);

        assertThat(response.salePrice()).isEqualByComparingTo("75.00");

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogItemRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
    }
}
