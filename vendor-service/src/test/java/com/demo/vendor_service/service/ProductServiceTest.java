package com.demo.vendor_service.service;

import com.demo.vendor_service.dto.CreateProductRequest;
import com.demo.vendor_service.dto.UpdateProductRequest;
import com.demo.vendor_service.exception.DuplicateSkuException;
import com.demo.vendor_service.exception.ForbiddenException;
import com.demo.vendor_service.exception.ProductNotFoundException;
import com.demo.vendor_service.models.Product;
import com.demo.vendor_service.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    void creatingAProductWithAnExistingSkuIsRejected() {

        when(productRepository.existsBySkuNumber("SKU-1")).thenReturn(true);

        CreateProductRequest request = new CreateProductRequest();
        request.setProductName("Widget");
        request.setSkuNumber("SKU-1");
        request.setUnitWeight(new BigDecimal("1.5"));
        request.setCostPrice(new BigDecimal("50.00"));

        assertThatThrownBy(() -> productService.create("VENDOR-1", request))
                .isInstanceOf(DuplicateSkuException.class);

        verify(productRepository, never()).save(any());
    }

    @Test
    void aVendorCannotUpdateAnotherVendorsProduct() {

        Product owned = new Product("VENDOR-2", "Widget", "SKU-1", "desc",
                new BigDecimal("1.5"), new BigDecimal("50.00"));

        when(productRepository.findByProductIdAndVendorId(1L, "VENDOR-1")).thenReturn(Optional.empty());
        when(productRepository.existsById(1L)).thenReturn(true);

        UpdateProductRequest request = new UpdateProductRequest();
        request.setProductName("Widget v2");
        request.setUnitWeight(new BigDecimal("2.0"));
        request.setCostPrice(new BigDecimal("55.00"));

        assertThatThrownBy(() -> productService.update("VENDOR-1", 1L, request))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void updatingAProductThatDoesNotExistAtAllIsNotFoundNotForbidden() {

        when(productRepository.findByProductIdAndVendorId(99L, "VENDOR-1")).thenReturn(Optional.empty());
        when(productRepository.existsById(99L)).thenReturn(false);

        UpdateProductRequest request = new UpdateProductRequest();
        request.setProductName("Widget");
        request.setUnitWeight(new BigDecimal("1.0"));
        request.setCostPrice(new BigDecimal("10.00"));

        assertThatThrownBy(() -> productService.update("VENDOR-1", 99L, request))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void updatingOwnProductSucceeds() {

        Product owned = new Product("VENDOR-1", "Widget", "SKU-1", "desc",
                new BigDecimal("1.5"), new BigDecimal("50.00"));

        when(productRepository.findByProductIdAndVendorId(1L, "VENDOR-1")).thenReturn(Optional.of(owned));

        UpdateProductRequest request = new UpdateProductRequest();
        request.setProductName("Widget v2");
        request.setDescription("updated");
        request.setUnitWeight(new BigDecimal("2.0"));
        request.setCostPrice(new BigDecimal("55.00"));

        var response = productService.update("VENDOR-1", 1L, request);

        assertThat(response.productName()).isEqualTo("Widget v2");
        assertThat(response.costPrice()).isEqualByComparingTo("55.00");
    }
}
