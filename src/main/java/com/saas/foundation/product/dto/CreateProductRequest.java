package com.saas.foundation.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String sku
) {
}
