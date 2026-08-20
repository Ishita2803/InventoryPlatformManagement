package com.demo.inventory_service.service;

import com.demo.inventory_service.dto.InventoryRequest;
import com.demo.inventory_service.dto.InventoryResponse;
import com.demo.inventory_service.dto.ProductRequest;
import com.demo.inventory_service.exception.InsufficientInventoryException;
import com.demo.inventory_service.exception.InventoryNotFoundException;
import com.demo.inventory_service.models.Inventory;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.repository.InventoryRepository;
import com.demo.inventory_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    @Transactional
    public Product createProduct(ProductRequest request) {

        if (productRepository.findBySku(request.getSku()).isPresent()) {
            throw new RuntimeException("Product with SKU already exists");
        }

        Product product = new Product();
        product.setSku(request.getSku());
        product.setName(request.getName());

        return productRepository.save(product);
    }

    @Transactional
    public InventoryResponse addInventory(InventoryRequest request) {

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() ->
                        new RuntimeException("Product not found"));

        Inventory inventory = inventoryRepository
                .findByProductIdAndWarehouseId(
                        product.getId(),
                        request.getWarehouseId()
                )
                .orElseGet(() -> {
                    Inventory newInventory = new Inventory();
                    newInventory.setProductId(product.getId());
                    newInventory.setWarehouseId(request.getWarehouseId());
                    newInventory.setAvailableQuantity(0);
                    newInventory.setReservedQuantity(0);
                    return newInventory;
                });

        inventory.setAvailableQuantity(
                inventory.getAvailableQuantity() + request.getQuantity()
        );

        Inventory saved = inventoryRepository.save(inventory);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventory(
            Long productId,
            String warehouseId
    ) {

        Inventory inventory = inventoryRepository
                .findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseThrow(() ->
                        new RuntimeException("Inventory not found"));

        return toResponse(inventory);
    }

    @Transactional
    public InventoryResponse reserveInventory(
            Long productId,
            String warehouseId,
            Integer quantity
    ) {

        Inventory inventory = inventoryRepository
                .findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseThrow(() ->
                        new InventoryNotFoundException(
                                "Inventory not found for productId="
                                        + productId
                                        + ", warehouseId="
                                        + warehouseId
                        )
                );

        if (inventory.getAvailableQuantity() < quantity) {
            throw new InsufficientInventoryException(
                    "Insufficient inventory. Available="
                            + inventory.getAvailableQuantity()
                            + ", requested="
                            + quantity
            );
        }

        inventory.setAvailableQuantity(
                inventory.getAvailableQuantity() - quantity
        );

        inventory.setReservedQuantity(
                inventory.getReservedQuantity() + quantity
        );

        Inventory savedInventory =
                inventoryRepository.save(inventory);

        return toResponse(savedInventory);
    }

    @Transactional
    public InventoryResponse releaseInventory(
            Long productId,
            String warehouseId,
            Integer quantity
    ) {

        Inventory inventory = inventoryRepository
                .findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseThrow(() ->
                        new InventoryNotFoundException(
                                "Inventory not found"
                        )
                );

        if (inventory.getReservedQuantity() < quantity) {
            throw new IllegalArgumentException(
                    "Cannot release more inventory than reserved"
            );
        }

        inventory.setReservedQuantity(
                inventory.getReservedQuantity() - quantity
        );

        inventory.setAvailableQuantity(
                inventory.getAvailableQuantity() + quantity
        );

        Inventory savedInventory =
                inventoryRepository.save(inventory);

        return toResponse(savedInventory);
    }

    private InventoryResponse toResponse(Inventory inventory) {

        return new InventoryResponse(
                inventory.getProductId(),
                inventory.getWarehouseId(),
                inventory.getAvailableQuantity(),
                inventory.getReservedQuantity()
        );
    }
}