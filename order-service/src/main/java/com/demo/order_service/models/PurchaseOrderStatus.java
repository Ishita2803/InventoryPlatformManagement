package com.demo.order_service.models;

/** No REJECTED state: the mock vendor always fulfills. A real vendor integration would
 * need one; this one doesn't lie about having a failure path it can't actually reach. */
public enum PurchaseOrderStatus {
    PENDING,
    FULFILLED
}
