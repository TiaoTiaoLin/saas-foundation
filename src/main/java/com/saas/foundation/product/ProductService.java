package com.saas.foundation.product;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.product.Product;
import com.saas.foundation.domain.product.ProductRepository;
import com.saas.foundation.product.dto.CreateProductRequest;
import com.saas.foundation.product.dto.ProductPageResponse;
import com.saas.foundation.product.dto.ProductResponse;
import com.saas.foundation.product.dto.UpdateProductRequest;

@Service
public class ProductService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private static final Sort NAME_THEN_SKU = Sort.by(
            Sort.Order.asc("name").ignoreCase(),
            Sort.Order.asc("sku").ignoreCase());

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public ProductPageResponse listAll(Pageable pageable) {
        return toPage(productRepository.findAll(ordered(pageable)));
    }

    @Transactional(readOnly = true)
    public ProductPageResponse listActive(Pageable pageable) {
        return toPage(productRepository.findByActiveTrue(ordered(pageable)));
    }

    /**
     * @param includeInactive owners and admins also match products that have been turned off
     */
    @Transactional(readOnly = true)
    public ProductPageResponse search(String query, boolean includeInactive, Pageable pageable) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search text is required");
        }
        if (trimmed.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search text must be at most 128 characters");
        }
        String pattern = likePattern(trimmed);
        Pageable ordered = ordered(pageable);
        Page<Product> products = includeInactive
                ? productRepository.search(pattern, ordered)
                : productRepository.searchActive(pattern, ordered);
        return toPage(products);
    }

    /**
     * @param includeInactive owners and admins can load a product that has been turned off;
     *                        members receive the same not-found response as for a missing id
     */
    @Transactional(readOnly = true)
    public ProductResponse getById(UUID id, boolean includeInactive) {
        return productRepository.findById(id)
                .filter(product -> includeInactive || product.isActive())
                .map(ProductResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU already exists for this tenant");
        }
        Product product = new Product(request.name().trim(), request.sku().trim());
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(UUID id, UpdateProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        if (request.name() != null && !request.name().isBlank()) {
            product.setName(request.name().trim());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public void delete(UUID id) {
        if (!productRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        productRepository.deleteById(id);
    }

    private static ProductPageResponse toPage(Page<Product> page) {
        return new ProductPageResponse(
                page.getContent().stream().map(ProductResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }

    /** Keeps name-then-SKU order and rejects a page size outside 1..{@link #MAX_PAGE_SIZE}. */
    private static Pageable ordered(Pageable requested) {
        int size = requested.getPageSize();
        if (size < 1) {
            size = DEFAULT_PAGE_SIZE;
        } else if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
        return PageRequest.of(Math.max(requested.getPageNumber(), 0), size, NAME_THEN_SKU);
    }

    /** Wraps {@code query} as a LIKE pattern, treating {@code %}, {@code _} and {@code \} as literals. */
    static String likePattern(String query) {
        String escaped = query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
