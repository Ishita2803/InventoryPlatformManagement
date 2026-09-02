package com.demo.auth_service.models;

/**
 * Only these four exist because only four kinds of user exist in Impulse:
 * the platform's own admin, and the three business roles that are "onboarded"
 * before they can do anything. New roles need a new value here AND a new entry in the
 * gateway's route-authorization map -- there is deliberately no wildcard/default role.
 */
public enum Role {
    ADMIN,
    VENDOR,
    CUSTOMER,
    CARRIER
}
