package com.saas.foundation.domain.product;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySku(String sku);

    Page<Product> findByActiveTrue(Pageable pageable);

    boolean existsBySku(String sku);

    /**
     * Case-insensitive substring match on name or SKU. The caller supplies a LIKE pattern
     * whose {@code %} and {@code _} wildcards are already escaped. Sort comes from {@code pageable}.
     */
    @Query("""
            select p from Product p
            where (lower(p.name) like lower(:pattern) escape '\\'
                or lower(p.sku) like lower(:pattern) escape '\\')
            """)
    Page<Product> search(@Param("pattern") String pattern, Pageable pageable);

    @Query("""
            select p from Product p
            where p.active = true
                and (lower(p.name) like lower(:pattern) escape '\\'
                    or lower(p.sku) like lower(:pattern) escape '\\')
            """)
    Page<Product> searchActive(@Param("pattern") String pattern, Pageable pageable);
}
