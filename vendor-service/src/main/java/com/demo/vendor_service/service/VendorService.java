package com.demo.vendor_service.service;

import com.demo.vendor_service.client.AuthServiceClient;
import com.demo.vendor_service.dto.OnboardVendorRequest;
import com.demo.vendor_service.dto.VendorResponse;
import com.demo.vendor_service.exception.VendorNotFoundException;
import com.demo.vendor_service.models.Vendor;
import com.demo.vendor_service.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VendorService {

    private final VendorRepository vendorRepository;
    private final AuthServiceClient authServiceClient;

    /**
     * Two writes to two services, deliberately not atomic -- see {@link AuthServiceClient}.
     * The Vendor row is created first and committed before the credential call, so a
     * failure here leaves a vendor admin can see and retry onboarding for, rather than a
     * half-created login with no vendor behind it.
     */
    @Transactional
    public VendorResponse onboard(OnboardVendorRequest request) {

        Vendor vendor = vendorRepository.save(new Vendor(request.getName()));

        authServiceClient.createCredential(
                request.getUsername(), request.getPassword(), "VENDOR", vendor.getVendorId());

        log.info("Onboarded vendor {} ({})", vendor.getVendorId(), vendor.getName());

        return toResponse(vendor);
    }

    public List<VendorResponse> listAll() {
        return vendorRepository.findAll().stream().map(this::toResponse).toList();
    }

    public VendorResponse get(String vendorId) {
        return toResponse(vendorRepository.findByVendorId(vendorId)
                .orElseThrow(() -> new VendorNotFoundException(vendorId)));
    }

    private VendorResponse toResponse(Vendor vendor) {
        return new VendorResponse(vendor.getVendorId(), vendor.getName(), vendor.getCreatedAt());
    }
}
