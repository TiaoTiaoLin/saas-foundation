package com.saas.foundation.audit;

import java.util.List;

public record AuditPageResponse(
        List<AuditEventResponse> items,
        int page,
        int size,
        long total
) {
}
