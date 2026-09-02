package com.demo.order_service.models;

/**
 * Why this purchase order exists. Only {@code STOCKING} is created directly by anything
 * this phase (D6) builds; {@code BACKORDER} (Phase D7, a sales order's shortfall) and
 * {@code DIRECT} (Phase D9, a direct order bypassing the warehouse entirely) are declared
 * now because {@code PurchaseOrder} needs a stable shape for both -- adding the enum value
 * later would be free, but this documents the extension point where it matters, not as an
 * afterthought.
 */
public enum PurchaseOrderPurpose {
    STOCKING,
    BACKORDER,
    DIRECT
}
