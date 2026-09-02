package com.demo.inventory_service.service;

import com.demo.inventory_service.client.VendorServiceClient;
import com.demo.inventory_service.dto.CatalogItemResponse;
import com.demo.inventory_service.dto.SetSalePriceRequest;
import com.demo.inventory_service.exception.CatalogItemNotFoundException;
import com.demo.inventory_service.models.CatalogItem;
import com.demo.inventory_service.repository.CatalogItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sets what WE charge for a vendor's sku. {@code vendorId} and {@code unitWeight} are
 * fetched from vendor-service and denormalized onto {@link CatalogItem} every time the
 * price is set -- cheap (admin sets a price far less often than customers place orders)
 * and it means Phase D7/D8 never need a synchronous call back to vendor-service per order.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogService {

    private final CatalogItemRepository catalogItemRepository;
    private final VendorServiceClient vendorServiceClient;

    @Transactional
    public CatalogItemResponse setSalePrice(SetSalePriceRequest request) {

        VendorServiceClient.VendorProduct vendorProduct =
                vendorServiceClient.getProductBySku(request.getSkuNumber());

        CatalogItem item = catalogItemRepository.findBySkuNumber(request.getSkuNumber())
                .orElse(null);

        if (item == null) {
            item = new CatalogItem(
                    request.getSkuNumber(), vendorProduct.vendorId(),
                    vendorProduct.unitWeight(), request.getSalePrice());
        } else {
            item.setVendorId(vendorProduct.vendorId());
            item.setUnitWeight(vendorProduct.unitWeight());
            item.updatePrice(request.getSalePrice());
        }

        catalogItemRepository.save(item);

        log.info("Set sale price for sku {} to {} (vendor {})",
                request.getSkuNumber(), request.getSalePrice(), vendorProduct.vendorId());

        return toResponse(item);
    }

    public List<CatalogItemResponse> listAll() {
        return catalogItemRepository.findAll().stream().map(this::toResponse).toList();
    }

    public CatalogItemResponse get(String sku) {
        return toResponse(catalogItemRepository.findBySkuNumber(sku)
                .orElseThrow(() -> new CatalogItemNotFoundException(sku)));
    }

    private CatalogItemResponse toResponse(CatalogItem item) {
        return new CatalogItemResponse(
                item.getSkuNumber(), item.getVendorId(), item.getUnitWeight(),
                item.getSalePrice(), item.getCreatedAt(), item.getUpdatedAt());
    }
}
