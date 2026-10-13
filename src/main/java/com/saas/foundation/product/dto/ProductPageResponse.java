package com.saas.foundation.product.dto;

import java.util.List;

public record ProductPageResponse(
        List<ProductResponse> items,
        int page,
        int size,
        long total
) {
}
