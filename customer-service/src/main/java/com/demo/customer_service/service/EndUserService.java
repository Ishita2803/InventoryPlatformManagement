package com.demo.customer_service.service;

import com.demo.customer_service.dto.AddressResponse;
import com.demo.customer_service.dto.CreateEndUserRequest;
import com.demo.customer_service.dto.EndUserResponse;
import com.demo.customer_service.exception.EndUserNotFoundException;
import com.demo.customer_service.models.Address;
import com.demo.customer_service.models.EndUser;
import com.demo.customer_service.repository.EndUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EndUserService {

    private final EndUserRepository endUserRepository;

    @Transactional
    public EndUserResponse create(String customerNo, CreateEndUserRequest request) {
        EndUser endUser = endUserRepository.save(new EndUser(
                customerNo, request.getName(),
                new Address(request.getShippingAddress().getLine(), request.getShippingAddress().getCity(),
                        request.getShippingAddress().getRegion())));
        return toResponse(endUser);
    }

    public List<EndUserResponse> list(String customerNo) {
        return endUserRepository.findByCustomerNo(customerNo).stream().map(this::toResponse).toList();
    }

    public EndUserResponse getByEndUserId(String endUserId) {
        return toResponse(endUserRepository.findByEndUserId(endUserId)
                .orElseThrow(() -> new EndUserNotFoundException("End user not found: " + endUserId)));
    }

    @Transactional
    public void delete(String customerNo, Long id) {
        EndUser endUser = endUserRepository.findByIdAndCustomerNo(id, customerNo)
                .orElseThrow(() -> new EndUserNotFoundException(id));
        endUserRepository.delete(endUser);
    }

    private EndUserResponse toResponse(EndUser endUser) {
        Address a = endUser.getShippingAddress();
        return new EndUserResponse(
                endUser.getEndUserId(), endUser.getCustomerNo(), endUser.getName(),
                new AddressResponse(a.getLine(), a.getCity(), a.getRegion()),
                endUser.getCreatedAt());
    }
}
