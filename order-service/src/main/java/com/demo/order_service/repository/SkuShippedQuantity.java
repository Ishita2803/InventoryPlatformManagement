package com.demo.order_service.repository;

/** JPQL constructor-expression projection for {@link OrderItemRepository}. */
public record SkuShippedQuantity(String skuNumber, Long totalQuantity) {
}
