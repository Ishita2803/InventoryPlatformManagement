package com.demo.vendor_service.service;

import com.demo.vendor_service.dto.CreateProductRequest;
import com.demo.vendor_service.dto.ProductResponse;
import com.demo.vendor_service.dto.UpdateProductRequest;
import com.demo.vendor_service.exception.DuplicateSkuException;
import com.demo.vendor_service.exception.ForbiddenException;
import com.demo.vendor_service.exception.ProductNotFoundException;
import com.demo.vendor_service.models.Product;
import com.demo.vendor_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Every mutation is scoped to the calling vendor's own {@code vendorId} -- taken from the
 * gateway-forwarded {@code X-User-Business-Id} header, never a client-supplied path or
 * body value, so one vendor cannot edit another's catalog by guessing a product id.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public ProductResponse create(String vendorId, CreateProductRequest request) {

        if (productRepository.existsBySkuNumber(request.getSkuNumber())) {
            throw new DuplicateSkuException(request.getSkuNumber());
        }

        Product product = productRepository.save(new Product(
                vendorId,
                request.getProductName(),
                request.getSkuNumber(),
                request.getDescription(),
                request.getUnitWeight(),
                request.getCostPrice()));

        log.info("Vendor {} created product {} (sku {})",
                vendorId, product.getProductId(), product.getSkuNumber());

        return toResponse(product);
    }

    @Transactional
    public ProductResponse update(String vendorId, Long productId, UpdateProductRequest request) {

        Product product = requireOwned(vendorId, productId);
        product.applyUpdate(
                request.getProductName(), request.getDescription(),
                request.getUnitWeight(), request.getCostPrice());

        log.info("Vendor {} updated product {}", vendorId, productId);

        return toResponse(product);
    }

    @Transactional
    public void delete(String vendorId, Long productId) {
        Product product = requireOwned(vendorId, productId);
        productRepository.delete(product);
        log.info("Vendor {} deleted product {}", vendorId, productId);
    }

    public List<ProductResponse> listForVendor(String vendorId) {
        return productRepository.findByVendorId(vendorId).stream().map(this::toResponse).toList();
    }

    public List<ProductResponse> listAll() {
        return productRepository.findAll().stream().map(this::toResponse).toList();
    }

    public ProductResponse get(Long productId) {
        return toResponse(productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId)));
    }

    public ProductResponse getBySku(String sku) {
        return toResponse(productRepository.findBySkuNumber(sku)
                .orElseThrow(() -> new ProductNotFoundException("Product not found for sku " + sku)));
    }

    /** Loads the product only if it exists AND belongs to this vendor -- one query, no
     * separate ownership check that could race with a concurrent delete. */
    private Product requireOwned(String vendorId, Long productId) {
        return productRepository.findByProductIdAndVendorId(productId, vendorId)
                .orElseThrow(() -> {
                    boolean existsAtAll = productRepository.existsById(productId);
                    if (existsAtAll) {
                        return new ForbiddenException("Product " + productId + " does not belong to vendor " + vendorId);
                    }
                    return new ProductNotFoundException(productId);
                });
    }

    private ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getProductId(), product.getVendorId(), product.getProductName(),
                product.getSkuNumber(), product.getDescription(), product.getUnitWeight(),
                product.getCostPrice(), product.getCreatedAt(), product.getUpdatedAt());
    }
}
