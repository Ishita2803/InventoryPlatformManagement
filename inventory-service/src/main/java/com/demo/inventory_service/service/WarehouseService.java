package com.demo.inventory_service.service;

import com.demo.inventory_service.dto.CreateWarehouseRequest;
import com.demo.inventory_service.dto.WarehouseResponse;
import com.demo.inventory_service.exception.DuplicateWarehouseIdException;
import com.demo.inventory_service.exception.WarehouseNotFoundException;
import com.demo.inventory_service.models.Warehouse;
import com.demo.inventory_service.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;

    @Transactional
    public WarehouseResponse create(CreateWarehouseRequest request) {

        if (warehouseRepository.existsByWarehouseId(request.getWarehouseId())) {
            throw new DuplicateWarehouseIdException(request.getWarehouseId());
        }

        Warehouse warehouse = warehouseRepository.save(new Warehouse(
                request.getWarehouseId(), request.getLocation(), request.getRegion()));

        log.info("Registered warehouse {} in region {}", warehouse.getWarehouseId(), warehouse.getRegion());

        return toResponse(warehouse);
    }

    public List<WarehouseResponse> listAll() {
        return warehouseRepository.findAll().stream().map(this::toResponse).toList();
    }

    public WarehouseResponse get(String warehouseId) {
        return toResponse(warehouseRepository.findByWarehouseId(warehouseId)
                .orElseThrow(() -> new WarehouseNotFoundException(warehouseId)));
    }

    private WarehouseResponse toResponse(Warehouse warehouse) {
        return new WarehouseResponse(
                warehouse.getWarehouseId(), warehouse.getLocation(), warehouse.getRegion(), warehouse.getCreatedAt());
    }
}
