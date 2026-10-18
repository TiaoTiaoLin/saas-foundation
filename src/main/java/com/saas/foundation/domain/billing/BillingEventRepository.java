package com.saas.foundation.domain.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BillingEventRepository extends JpaRepository<BillingEvent, String> {
}
