package com.demo.customer_service.service;

import com.demo.customer_service.dto.AddressDto;
import com.demo.customer_service.dto.CreateEndUserRequest;
import com.demo.customer_service.exception.EndUserNotFoundException;
import com.demo.customer_service.models.Address;
import com.demo.customer_service.models.EndUser;
import com.demo.customer_service.repository.EndUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndUserServiceTest {

    @Mock
    private EndUserRepository endUserRepository;

    @InjectMocks
    private EndUserService endUserService;

    @Test
    void creatingAnEndUserCarriesTheRegionThroughToTheResponse() {

        when(endUserRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        CreateEndUserRequest request = new CreateEndUserRequest();
        request.setName("Vijay Sales Mumbai");
        AddressDto addr = new AddressDto();
        addr.setLine("123 Market Rd");
        addr.setCity("Mumbai");
        addr.setRegion("MUMBAI");
        request.setShippingAddress(addr);

        var response = endUserService.create("CUSTOMER-1", request);

        assertThat(response.customerNo()).isEqualTo("CUSTOMER-1");
        assertThat(response.name()).isEqualTo("Vijay Sales Mumbai");
        assertThat(response.shippingAddress().region()).isEqualTo("MUMBAI");
        assertThat(response.endUserId()).isNotBlank();
    }

    @Test
    void deletingAnEndUserThatBelongsToAnotherCustomerIsNotFound() {

        when(endUserRepository.findByIdAndCustomerNo(1L, "CUSTOMER-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> endUserService.delete("CUSTOMER-1", 1L))
                .isInstanceOf(EndUserNotFoundException.class);
    }

    @Test
    void deletingOwnEndUserSucceeds() {

        EndUser endUser = new EndUser("CUSTOMER-1", "Vijay Sales Pune",
                new Address("456 Main St", "Pune", "PUNE"));

        when(endUserRepository.findByIdAndCustomerNo(1L, "CUSTOMER-1")).thenReturn(Optional.of(endUser));

        endUserService.delete("CUSTOMER-1", 1L);
    }
}
