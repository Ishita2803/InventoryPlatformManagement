package com.demo.customer_service.service;

import com.demo.customer_service.client.AuthServiceClient;
import com.demo.customer_service.dto.*;
import com.demo.customer_service.exception.CustomerNotFoundException;
import com.demo.customer_service.models.Address;
import com.demo.customer_service.models.Customer;
import com.demo.customer_service.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final AuthServiceClient authServiceClient;

    @Transactional
    public CustomerResponse onboard(OnboardCustomerRequest request) {

        Customer customer = customerRepository.save(new Customer(
                request.getName(),
                request.getEmail(),
                toAddress(request.getDefaultBillingAddress()),
                toAddress(request.getDefaultShippingAddress())));

        authServiceClient.createCredential(
                request.getUsername(), request.getPassword(), "CUSTOMER", customer.getCustomerNo());

        log.info("Onboarded customer {} ({})", customer.getCustomerNo(), customer.getName());

        return toResponse(customer);
    }

    public List<CustomerResponse> listAll() {
        return customerRepository.findAll().stream().map(this::toResponse).toList();
    }

    public CustomerResponse get(String customerNo) {
        return toResponse(customerRepository.findByCustomerNo(customerNo)
                .orElseThrow(() -> new CustomerNotFoundException(customerNo)));
    }

    private Address toAddress(AddressDto dto) {
        return new Address(dto.getLine(), dto.getCity(), dto.getRegion());
    }

    private AddressResponse toAddressResponse(Address address) {
        return new AddressResponse(address.getLine(), address.getCity(), address.getRegion());
    }

    private CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
                customer.getCustomerNo(), customer.getName(), customer.getEmail(),
                toAddressResponse(customer.getDefaultBillingAddress()),
                toAddressResponse(customer.getDefaultShippingAddress()),
                customer.getCreatedAt());
    }
}
