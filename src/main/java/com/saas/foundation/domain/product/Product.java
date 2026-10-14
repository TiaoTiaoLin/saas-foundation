package com.saas.foundation.domain.product;

import com.saas.foundation.domain.common.TenantScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "products",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_products_tenant_sku",
                columnNames = {"tenant_id", "sku"}
        )
)
@Getter
@Setter
public class Product extends TenantScopedEntity {

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private boolean active = true;

    /** Required by JPA. */
    public Product() {
    }

    public Product(String name, String sku) {
        this.name = name;
        this.sku = sku;
        this.active = true;
    }
}
