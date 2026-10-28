package com.buy01.product_service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.buy01.product_service.dtos.req.ProductSearchRequest;
import com.buy01.product_service.models.Product;

@ExtendWith(MockitoExtension.class)
class ProductSearchRepositoryTest {

    @Mock private MongoTemplate mongoTemplate;
    @InjectMocks private ProductSearchRepository repository;

    @Test
    void combinesTextCategoryPriceAndStockFilters() {
        var filters = new ProductSearchRequest(" phone ", " Electronics ", new BigDecimal("500"),
                new BigDecimal("3000"), true, null, null, null);

        var query = repository.buildQuery(filters).getQueryObject();

        assertThat(query.get("$text", Document.class).getString("$search")).isEqualTo("phone");
        assertThat(query.getString("category")).isEqualTo("electronics");
        assertThat(query.get("price", Document.class))
                .containsEntry("$gte", 500.0)
                .containsEntry("$lte", 3000.0);
        assertThat(query.get("quantity", Document.class)).containsEntry("$gt", 0);
    }

    @Test
    void absentOrBlankFiltersIncludeAllProducts() {
        var filters = new ProductSearchRequest("  ", "  ", null, null, false, null, null, null);
        assertThat(repository.buildQuery(filters).getQueryObject()).isEmpty();
    }

    @Test
    void supportsOneSidedPriceRanges() {
        var minimum = new ProductSearchRequest(null, null, BigDecimal.TEN, null, null, null, null, null);
        var maximum = new ProductSearchRequest(null, null, null, BigDecimal.TEN, null, null, null, null);

        assertThat(repository.buildQuery(minimum).getQueryObject().get("price", Document.class))
                .containsOnlyKeys("$gte");
        assertThat(repository.buildQuery(maximum).getQueryObject().get("price", Document.class))
                .containsOnlyKeys("$lte");
    }

    @Test
    void uncategorizedFilterIncludesLegacyMissingAndBlankCategories() {
        var filters = new ProductSearchRequest(null, "Uncategorized", null, null, null, null, null, null);
        var alternatives = repository.buildQuery(filters).getQueryObject().getList("$or", Document.class);
        assertThat(alternatives).hasSize(3);
        assertThat(alternatives.get(0)).containsEntry("category", "uncategorized");
        assertThat(alternatives.get(1)).containsEntry("category", null);
        assertThat(alternatives.get(2).get("category")).hasToString("^\\s*$");
    }

    @ParameterizedTest
    @CsvSource({"price-asc,price,1", "price-desc,price,-1", "name-asc,name,1",
            "newest,createdAt,-1", "featured,createdAt,-1"})
    void countsAllMatchesBeforePagingAndSortsDeterministically(String sort, String field, int direction) {
        var filters = new ProductSearchRequest(null, null, null, null, null, sort, 2, 2);
        when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(7L);
        when(mongoTemplate.find(any(Query.class), eq(Product.class)))
                .thenReturn(List.of(Product.builder().id("p5").build(), Product.builder().id("p6").build()));

        var page = repository.search(filters);

        var countQuery = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(countQuery.capture(), eq(Product.class));
        assertThat(countQuery.getValue().getSkip()).isZero();
        assertThat(countQuery.getValue().isLimited()).isFalse();
        var findQuery = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQuery.capture(), eq(Product.class));
        assertThat(findQuery.getValue().getSkip()).isEqualTo(4);
        assertThat(findQuery.getValue().getLimit()).isEqualTo(2);
        assertThat(findQuery.getValue().getSortObject()).containsEntry(field, direction).containsEntry("id", 1);
        assertThat(page.getTotalElements()).isEqualTo(7);
        assertThat(page.getTotalPages()).isEqualTo(4);
    }

    @Test
    void pageBeyondLastPageKeepsTotalsAndReturnsEmptyContent() {
        when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(2L);
        when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of());
        var page = repository.search(new ProductSearchRequest(null, null, null, null, null, null, 5, 12));
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(1);
    }
}
