package com.buy01.product_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.buy01.product_service.dtos.req.StockRequest;
import com.buy01.product_service.exception.ConflictException;
import com.buy01.product_service.exception.ResourceNotFoundException;
import com.buy01.product_service.models.Product;
import com.buy01.product_service.repository.ProductRepository;
import com.mongodb.client.result.UpdateResult;

class StockServiceTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final StockService service = new StockService(mongoTemplate, productRepository);

    private static UpdateResult modified(long count) {
        return UpdateResult.acknowledged(count, count, null);
    }

    private void stockAvailable(String productId, boolean available) {
        when(mongoTemplate.updateFirst(
                org.mockito.ArgumentMatchers.argThat((Query q) -> q != null
                        && productId.equals(q.getQueryObject().get("id"))
                        && q.getQueryObject().containsKey("quantity")),
                any(Update.class), eq(Product.class)))
                .thenReturn(modified(available ? 1 : 0));
    }

    @Test
    void reserve_decrementsOnlyWhenEnoughStock() {
        stockAvailable("p1", true);

        service.reserve(List.of(new StockRequest.Item("p1", 2), new StockRequest.Item("p1", 1)));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq(Product.class));
        // duplicates are merged into one conditional update
        assertThat(query.getValue().getQueryObject())
                .isEqualTo(new Document("id", "p1").append("quantity", new Document("$gte", 3)));
        assertThat(update.getValue().getUpdateObject().get("$inc", Document.class))
                .isEqualTo(new Document("quantity", -3));
    }

    @Test
    void reserve_whenOneProductIsShort_putsBackTheOthersAndThrowsConflict() {
        stockAvailable("p1", true);
        stockAvailable("p2", false);
        when(productRepository.findById("p2"))
                .thenReturn(Optional.of(Product.builder().id("p2").name("Cable").quantity(1).build()));

        List<StockRequest.Item> items = List.of(new StockRequest.Item("p1", 2), new StockRequest.Item("p2", 5));

        assertThatThrownBy(() -> service.reserve(items))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Not enough stock for Cable: 1 available");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, times(3)).updateFirst(query.capture(), update.capture(), eq(Product.class));
        assertThat(query.getAllValues().get(2).getQueryObject()).isEqualTo(new Document("id", "p1"));
        assertThat(update.getAllValues().get(2).getUpdateObject().get("$inc", Document.class))
                .isEqualTo(new Document("quantity", 2));
    }

    @Test
    void reserve_unknownProduct_throwsNotFound() {
        stockAvailable("missing", false);
        when(productRepository.findById("missing")).thenReturn(Optional.empty());

        List<StockRequest.Item> items = List.of(new StockRequest.Item("missing", 1));

        assertThatThrownBy(() -> service.reserve(items))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product not found: missing");
    }

    @Test
    void release_incrementsEachProduct() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Product.class)))
                .thenReturn(modified(1));

        service.release(List.of(new StockRequest.Item("p1", 2), new StockRequest.Item("p2", 1)));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, times(2)).updateFirst(query.capture(), update.capture(), eq(Product.class));
        assertThat(query.getAllValues().get(0).getQueryObject()).isEqualTo(new Document("id", "p1"));
        assertThat(update.getAllValues().get(0).getUpdateObject().get("$inc", Document.class))
                .isEqualTo(new Document("quantity", 2));
        assertThat(update.getAllValues().get(1).getUpdateObject().get("$inc", Document.class))
                .isEqualTo(new Document("quantity", 1));
    }
}
