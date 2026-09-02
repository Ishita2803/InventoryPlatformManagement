package com.demo.customer_service.controller;

import com.demo.customer_service.dto.CreateEndUserRequest;
import com.demo.customer_service.dto.EndUserResponse;
import com.demo.customer_service.service.EndUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customer/end-users")
@RequiredArgsConstructor
public class EndUserController {

    private final EndUserService endUserService;

    @PostMapping
    public ResponseEntity<EndUserResponse> create(
            @RequestHeader("X-User-Business-Id") String customerNo,
            @Valid @RequestBody CreateEndUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(endUserService.create(customerNo, request));
    }

    @GetMapping
    public ResponseEntity<List<EndUserResponse>> list(
            @RequestHeader("X-User-Business-Id") String customerNo) {
        return ResponseEntity.ok(endUserService.list(customerNo));
    }

    /** Not gated to a customer's own id at the path level -- this is used internally by
     * order-service (Phase D7) to resolve an end user's shipping address by id, the same
     * cross-service, not-through-the-gateway reach vendor-service's product lookup uses. */
    @GetMapping("/by-end-user-id/{endUserId}")
    public ResponseEntity<EndUserResponse> getByEndUserId(@PathVariable String endUserId) {
        return ResponseEntity.ok(endUserService.getByEndUserId(endUserId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-User-Business-Id") String customerNo,
            @PathVariable Long id) {
        endUserService.delete(customerNo, id);
        return ResponseEntity.noContent().build();
    }
}
