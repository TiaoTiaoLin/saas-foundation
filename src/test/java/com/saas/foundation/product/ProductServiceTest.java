package com.saas.foundation.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.product.ProductRepository;
import com.saas.foundation.product.dto.ProductPageResponse;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository products;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(products);
    }

    @Test
    void searchMatchesNameOrSkuAndEscapesLikeWildcards() {
        when(products.search(eq("%100\\%\\_off\\\\pack%"), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        assertThat(service.search("  100%_off\\pack  ", true, PageRequest.of(0, 20)).items()).isEmpty();

        verify(products).search(eq("%100\\%\\_off\\\\pack%"), any(Pageable.class));
        verify(products, never()).searchActive(any(), any());
    }

    @Test
    void memberSearchOmitsInactiveProducts() {
        when(products.searchActive(eq("%bolt%"), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        service.search("bolt", false, PageRequest.of(0, 20));

        verify(products).searchActive(eq("%bolt%"), any(Pageable.class));
        verify(products, never()).search(any(), any());
    }

    @Test
    void searchCapsPageSizeAndKeepsTheRequestedPage() {
        when(products.search(eq("%bolt%"), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        ProductPageResponse response = service.search("bolt", true, PageRequest.of(2, 500));

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(ProductService.MAX_PAGE_SIZE);
        verify(products).search(eq("%bolt%"), eq(PageRequest.of(2, ProductService.MAX_PAGE_SIZE,
                Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("sku").ignoreCase()))));
    }

    @Test
    void blankSearchIsRejected() {
        assertThatThrownBy(() -> service.search("   ", true, PageRequest.of(0, 20)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(products, never()).search(any(), any());
    }
}
