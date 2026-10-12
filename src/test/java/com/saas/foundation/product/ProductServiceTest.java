package com.saas.foundation.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.product.ProductRepository;

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
        when(products.search("%100\\%\\_off\\\\pack%")).thenReturn(List.of());

        assertThat(service.search("  100%_off\\pack  ", true)).isEmpty();

        verify(products).search("%100\\%\\_off\\\\pack%");
        verify(products, never()).searchActive("%100\\%\\_off\\\\pack%");
    }

    @Test
    void memberSearchOmitsInactiveProducts() {
        when(products.searchActive("%bolt%")).thenReturn(List.of());

        service.search("bolt", false);

        verify(products).searchActive("%bolt%");
        verify(products, never()).search("%bolt%");
    }

    @Test
    void blankSearchIsRejected() {
        assertThatThrownBy(() -> service.search("   ", true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(products, never()).search(any());
    }
}
