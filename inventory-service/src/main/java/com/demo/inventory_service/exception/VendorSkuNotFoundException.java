package com.demo.inventory_service.exception;

/** The sku doesn't exist in ANY vendor's catalog (vendor-service returned 404) --
 * distinct from {@link CatalogItemNotFoundException}, which means the sku exists at a
 * vendor but Impulse hasn't set a sale price for it yet. */
public class VendorSkuNotFoundException extends RuntimeException {

    public VendorSkuNotFoundException(String sku) {
        super("No vendor product found for sku: " + sku);
    }
}
