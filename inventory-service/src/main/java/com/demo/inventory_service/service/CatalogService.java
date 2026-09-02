package com.demo.inventory_service.service;

import com.demo.inventory_service.client.VendorServiceClient;
import com.demo.inventory_service.dto.CatalogItemResponse;
import com.demo.inventory_service.dto.SetSalePriceRequest;
import com.demo.inventory_service.exception.CatalogItemNotFoundException;
import com.demo.inventory_service.models.CatalogItem;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.repository.CatalogItemRepository;
import com.demo.inventory_service.repository.ProductRepository;
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
 *
 * <p>Also upserts this service's own {@code Product} row (Phase 1) for the sku if one
 * doesn't exist yet. Without this, setting a sale price for a brand-new sku would leave
 * {@code Inventory} with nowhere to record stock for it, and Phase D6's purchase-order
 * fulfillment would fail with {@code ProductNotFoundException} the first time anyone
 * tried to stock it -- setting a sale price is the moment "admin decided to sell this
 * sku" actually happens, so it's the right place to register it, not an afterthought
 * discovered when fulfillment broke.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogService {

    private final CatalogItemRepository catalogItemRepository;
    private final ProductRepository productRepository;
    private final VendorServiceClient vendorServiceClient;

    @Transactional
    public CatalogItemResponse setSalePrice(SetSalePriceRequest request) {

        VendorServiceClient.VendorProduct vendorProduct =
                vendorServiceClient.getProductBySku(request.getSkuNumber());

        if (productRepository.findBySku(request.getSkuNumber()).isEmpty()) {
            Product product = new Product();
            product.setSku(request.getSkuNumber());
            product.setName(vendorProduct.productName());
            productRepository.save(product);
        }

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
