package com.saas.foundation.product.dto;

import java.time.Instant;
import java.util.UUID;

import com.saas.foundation.domain.product.Product;

public record ProductResponse(
        UUID id,
        UUID tenantId,
        String name,
        String sku,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getTenantId(),
                product.getName(),
                product.getSku(),
                product.isActive(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
