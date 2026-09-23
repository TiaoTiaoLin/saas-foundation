package com.saas.foundation.product.dto;

import jakarta.validation.constraints.Size;

public record UpdateProductRequest(
        @Size(max = 128) String name,
        Boolean active
) {
}
