package com.demo.order_service.controller;

import com.demo.order_service.dto.ProfitReportResponse;
import com.demo.order_service.service.ProfitReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase D11: admin-only profit reporting, surfaced in admin.html. */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final ProfitReportService profitReportService;

    @GetMapping("/profit")
    public ResponseEntity<ProfitReportResponse> profitReport() {
        return ResponseEntity.ok(profitReportService.generate());
    }
}
