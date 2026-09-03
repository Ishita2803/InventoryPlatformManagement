package com.demo.payment_service.service;

import com.demo.payment_service.client.CarrierServiceClient;
import com.demo.payment_service.dto.InvoiceRequest;
import com.demo.payment_service.dto.InvoiceResponse;
import com.demo.payment_service.exception.InvoiceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase D8's actual arithmetic: shipQuantity x salePrice per line, plus one weight-based
 * carrier surcharge for the whole order -- and the same idempotency guarantee
 * {@code PaymentServiceTest} already proves for a charge, now proved for an invoice.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    private CarrierServiceClient carrierServiceClient;

    private InvoiceService invoiceService;

    @BeforeEach
    void setUp() {
        invoiceService = new InvoiceService(carrierServiceClient);
    }

    @Test
    @DisplayName("lineTotal is shipQuantity x unitPrice summed across lines, plus the carrier's weight surcharge")
    void computesLineTotalPlusWeightSurcharge() {

        when(carrierServiceClient.surchargeFor(eq("BLUEDART"), eq(new BigDecimal("5.500"))))
                .thenReturn(new BigDecimal("15.00"));

        InvoiceRequest request = new InvoiceRequest("ORDER-1", "BLUEDART", List.of(
                new InvoiceRequest.Line("SKU-1", 3, new BigDecimal("70.00"), new BigDecimal("1.500")),
                new InvoiceRequest.Line("SKU-2", 2, new BigDecimal("10.00"), new BigDecimal("0.500"))));

        InvoiceResponse response = invoiceService.generate(request);

        // lineTotal = 3*70 + 2*10 = 230.00; totalWeight = 3*1.5 + 2*0.5 = 5.500kg
        assertThat(response.lineTotal()).isEqualByComparingTo("230.00");
        assertThat(response.totalWeight()).isEqualByComparingTo("5.500");
        assertThat(response.weightSurcharge()).isEqualByComparingTo("15.00");
        assertThat(response.totalAmount()).isEqualByComparingTo("245.00");
        assertThat(response.orderId()).isEqualTo("ORDER-1");
    }

    @Test
    @DisplayName("a repeated invoice request for the same order returns the original decision, without recomputing")
    void repeatedInvoiceRequestIsIdempotent() {

        when(carrierServiceClient.surchargeFor(any(), any())).thenReturn(new BigDecimal("5.00"));

        InvoiceRequest request = new InvoiceRequest("ORDER-2", "DTDC", List.of(
                new InvoiceRequest.Line("SKU-1", 1, new BigDecimal("50.00"), new BigDecimal("1.000"))));

        InvoiceResponse first = invoiceService.generate(request);
        InvoiceResponse second = invoiceService.generate(request);

        assertThat(second.invoiceId()).isEqualTo(first.invoiceId());
        verify(carrierServiceClient, times(1)).surchargeFor(any(), any());
    }

    @Test
    @DisplayName("the billing screen's GET returns the same invoice generate() already produced")
    void getByOrderIdReturnsThePreviouslyGeneratedInvoice() {

        when(carrierServiceClient.surchargeFor(any(), any())).thenReturn(new BigDecimal("5.00"));

        InvoiceRequest request = new InvoiceRequest("ORDER-3", "DTDC", List.of(
                new InvoiceRequest.Line("SKU-1", 1, new BigDecimal("50.00"), new BigDecimal("1.000"))));

        InvoiceResponse generated = invoiceService.generate(request);
        InvoiceResponse fetched = invoiceService.getByOrderId("ORDER-3");

        assertThat(fetched.invoiceId()).isEqualTo(generated.invoiceId());
    }

    @Test
    @DisplayName("an order with no invoice yet -- e.g. wholly backordered -- is a 404, not a null")
    void getByOrderIdThrowsWhenNoInvoiceExists() {

        assertThat(catchThrowable(() -> invoiceService.getByOrderId("NEVER-INVOICED")))
                .isInstanceOf(InvoiceNotFoundException.class);
    }
}
