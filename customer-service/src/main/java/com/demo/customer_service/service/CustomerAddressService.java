package com.demo.customer_service.service;

import com.demo.customer_service.dto.AddressResponse;
import com.demo.customer_service.dto.CreateAddressRequest;
import com.demo.customer_service.dto.CustomerAddressResponse;
import com.demo.customer_service.exception.AddressNotFoundException;
import com.demo.customer_service.models.Address;
import com.demo.customer_service.models.CustomerAddress;
import com.demo.customer_service.repository.CustomerAddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Same ownership shape as vendor-service's {@code ProductService}: every query is
 * scoped by {@code customerNo} at the repository level, never a separate check after an
 * unscoped lookup. */
@Service
@RequiredArgsConstructor
public class CustomerAddressService {

    private final CustomerAddressRepository addressRepository;

    @Transactional
    public CustomerAddressResponse add(String customerNo, CreateAddressRequest request) {
        CustomerAddress address = addressRepository.save(new CustomerAddress(
                customerNo, request.getLabel(),
                new Address(request.getAddress().getLine(), request.getAddress().getCity(), request.getAddress().getRegion())));
        return toResponse(address);
    }

    public List<CustomerAddressResponse> list(String customerNo) {
        return addressRepository.findByCustomerNo(customerNo).stream().map(this::toResponse).toList();
    }

    @Transactional
    public void delete(String customerNo, Long addressId) {
        CustomerAddress address = addressRepository.findByIdAndCustomerNo(addressId, customerNo)
                .orElseThrow(() -> new AddressNotFoundException(addressId));
        addressRepository.delete(address);
    }

    private CustomerAddressResponse toResponse(CustomerAddress address) {
        Address a = address.getAddress();
        return new CustomerAddressResponse(
                address.getId(), address.getCustomerNo(), address.getLabel(),
                new AddressResponse(a.getLine(), a.getCity(), a.getRegion()),
                address.getCreatedAt());
    }
}
