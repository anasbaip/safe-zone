package com.buy01.order_service.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.buy01.order_service.client.*;
import com.buy01.order_service.dtos.common.ShippingAddressDto;
import com.buy01.order_service.dtos.req.CheckoutRequest;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.exception.*;
import com.buy01.order_service.models.*;
import com.buy01.order_service.repository.*;
import com.buy01.order_service.security.AuthenticatedUser;

class CartServiceTest {
    CartRepository carts = mock(CartRepository.class);
    CheckoutReceiptRepository receipts = mock(CheckoutReceiptRepository.class);
    ProductClient products = mock(ProductClient.class);
    OrdersClient orders = mock(OrdersClient.class);
    CartService service = new CartService(carts, receipts, products, orders);
    AuthenticatedUser user = new AuthenticatedUser("client", List.of("CLIENT"));
    ShippingAddressDto address = new ShippingAddressDto("Jane", "123", "Street", "City", "100", "Morocco");
    Cart cart;
    Map<String, CheckoutReceipt> history = new HashMap<>();

    @BeforeEach void setup() {
        cart = new Cart(user.userId()); cart.setVersion(0L); cart.getItems().put("p1", 2);
        when(carts.findById("client")).thenAnswer(i -> Optional.of(cart));
        when(carts.save(any())).thenAnswer(i -> { Cart c = i.getArgument(0); c.setVersion(c.getVersion() == null ? 0 : c.getVersion() + 1); return c; });
        when(receipts.findById(anyString())).thenAnswer(i -> Optional.ofNullable(history.get(i.getArgument(0))));
        when(receipts.save(any())).thenAnswer(i -> { CheckoutReceipt r = i.getArgument(0); history.put(r.id(), r); return r; });
        when(products.get("p1")).thenReturn(product("10.25", 10));
    }
    ProductClient.Product product(String price, int stock) { return new ProductClient.Product("p1", "Product", new BigDecimal(price), stock, List.of()); }
    CheckoutRequest request() { return new CheckoutRequest(address, service.review(user).reviewToken()); }
    OrderResponse order() { return new OrderResponse("order-1", "client", OrderStatus.PENDING, List.of(), address,
        PaymentMethod.CASH_ON_DELIVERY, 2, new BigDecimal("20.50"), List.of(), null, null); }

    @Test void computesServerPricesAndUsesOnlyAuthenticatedCart() {
        var result = service.get(user);
        assertThat(result.totalAmount()).isEqualByComparingTo("20.50");
        verify(carts).findById("client");
    }
    @Test void clientCannotReadAnotherCart() {
        var other = new AuthenticatedUser("other", List.of("CLIENT"));
        assertThat(service.get(other).items()).isEmpty();
        verify(carts).findById("other");
    }
    @Test void anonymousAndSellersAreRejected() {
        assertThatThrownBy(() -> service.get(null)).isInstanceOf(UnauthorizedException.class);
        var seller = new AuthenticatedUser("seller", List.of("SELLER"));
        assertThatThrownBy(() -> service.get(seller)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(products);
    }
    @Test void addsAndUpdatesQuantities() {
        assertThat(service.setItem("p1", 2, true, user).items().get(0).quantity()).isEqualTo(4);
        assertThat(service.setItem("p1", 1, false, user).items().get(0).quantity()).isEqualTo(1);
    }
    @Test void rejectsInvalidQuantitiesAndInsufficientStock() {
        for (int n : new int[]{0, -1, 101}) assertThatThrownBy(() -> service.setItem("p1", n, false, user)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setItem("p1", 11, false, user)).isInstanceOf(ConflictException.class);
        verify(carts, never()).save(any());
    }
    @Test void deletedProductsRemainRemovable() {
        when(products.get("p1")).thenReturn(null);
        assertThat(service.get(user).items().get(0).available()).isFalse();
        assertThatThrownBy(() -> service.review(user)).isInstanceOf(ConflictException.class);
        assertThat(service.remove("p1", user).items()).isEmpty();
    }
    @Test void clearEmptiesTheCart() { assertThat(service.clear(user).items()).isEmpty(); }
    @Test void productOutageNeverSubmitsAnOrder() {
        when(products.get("p1")).thenThrow(new UpstreamServiceException("down"));
        var request = new CheckoutRequest(address, "old");
        assertThatThrownBy(() -> service.checkout(request, "key", user)).isInstanceOf(UpstreamServiceException.class);
        verifyNoInteractions(orders);
        assertThat(cart.getItems()).hasSize(1);
    }
    @Test void changedPricesRequireReviewAgain() {
        var request = request(); when(products.get("p1")).thenReturn(product("11.00", 10));
        assertThatThrownBy(() -> service.checkout(request, "key", user)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(orders);
    }
    @Test void changedStockAndEmptyCartsCannotCheckout() {
        var request = request(); when(products.get("p1")).thenReturn(product("10.25", 1));
        assertThatThrownBy(() -> service.checkout(request, "key", user)).isInstanceOf(ConflictException.class);
        cart.getItems().clear();
        assertThatThrownBy(() -> service.checkout(request, "key", user)).isInstanceOf(BadRequestException.class);
    }
    @Test void successfulConfirmationPersistsReceiptThenClearsAndReplayDoesNotCallOrders() {
        var request = request(); when(orders.create(any(), eq("key"), eq(user))).thenReturn(order());
        assertThat(service.checkout(request, "key", user).id()).isEqualTo("order-1");
        assertThat(cart.getItems()).isEmpty();
        assertThat(service.checkout(request, "key", user).id()).isEqualTo("order-1");
        verify(orders, times(1)).create(argThat(r -> r.expectedTotal().compareTo(new BigDecimal("20.50")) == 0
            && r.items().get(0).quantity() == 2 && r.paymentMethod() == PaymentMethod.CASH_ON_DELIVERY), eq("key"), eq(user));
        var sequence = inOrder(orders, receipts, carts);
        sequence.verify(carts).save(any()); sequence.verify(orders).create(any(), anyString(), any());
        sequence.verify(receipts).save(any()); sequence.verify(carts).save(any());
    }
    @Test void uncertainOrderFailureRetainsImmutableSnapshotForRetry() {
        var request = request();
        when(orders.create(any(), eq("key"), eq(user))).thenThrow(new UpstreamServiceException("timeout")).thenReturn(order());
        assertThatThrownBy(() -> service.checkout(request, "key", user)).isInstanceOf(UpstreamServiceException.class);
        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getPendingKey()).isEqualTo("key");
        assertThatThrownBy(() -> service.clear(user)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.checkout(request, "different-key", user)).isInstanceOf(ConflictException.class);
        when(products.get("p1")).thenThrow(new UpstreamServiceException("product down"));
        assertThat(service.get(user).pending().key()).isEqualTo("key");
        assertThat(service.checkout(request, "key", user).id()).isEqualTo("order-1");
    }
    @Test void definitiveOrderRefusalUnlocksTheCartForAnotherReview() {
        var request = request();
        when(orders.create(any(), eq("key"), eq(user))).thenThrow(new OrderRejectedException("Prices changed"));
        assertThatThrownBy(() -> service.checkout(request, "key", user)).isInstanceOf(OrderRejectedException.class);
        assertThat(cart.getPendingKey()).isNull();
        assertThat(cart.getPendingRequest()).isNull();
        assertThat(cart.getItems()).containsEntry("p1", 2);
        assertThat(history).isEmpty();
        assertThat(service.clear(user).items()).isEmpty();
    }
    @Test void changedAddressCannotReuseCompletedKey() {
        var request = request(); when(orders.create(any(), anyString(), any())).thenReturn(order());
        service.checkout(request, "key", user);
        var changed = new ShippingAddressDto("Other", "1", "Street", "City", "100", "Morocco");
        var changedRequest = new CheckoutRequest(changed, request.reviewToken());
        assertThatThrownBy(() -> service.checkout(changedRequest, "key", user)).isInstanceOf(ConflictException.class);
    }
    @Test void optimisticConflictDoesNotSubmitOrder() {
        var request = request();
        doThrow(new org.springframework.dao.OptimisticLockingFailureException("concurrent")).when(carts).save(any());
        assertThatThrownBy(() -> service.checkout(request, "key", user)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        verifyNoInteractions(orders);
    }
    @Test void failedClearCanBeRecoveredFromPersistedReceipt() {
        var request = request(); when(orders.create(any(), anyString(), any())).thenReturn(order());
        doAnswer(i -> i.getArgument(0)).doThrow(new org.springframework.dao.OptimisticLockingFailureException("conflict")).when(carts).save(any());
        assertThatThrownBy(() -> service.checkout(request, "key", user)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(history).hasSize(1);
        assertThat(service.checkout(request, "key", user).id()).isEqualTo("order-1");
        verify(orders, times(1)).create(any(), anyString(), any());
    }
    @Test void failureToPersistReceiptNeverClearsCart() {
        var request = request(); when(orders.create(any(), anyString(), any())).thenReturn(order());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database down")).when(receipts).save(any());
        assertThatThrownBy(() -> service.checkout(request, "key", user))
            .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(cart.getItems()).containsEntry("p1", 2);
        assertThat(cart.getPendingKey()).isEqualTo("key");
        verify(carts, times(1)).save(any());
    }
    @Test void oldReceiptReplayNeverClearsNewerCartContents() {
        var request = request(); when(orders.create(any(), anyString(), any())).thenReturn(order());
        service.checkout(request, "key", user);
        cart.getItems().put("p1", 3);
        assertThat(service.checkout(request, "key", user).id()).isEqualTo("order-1");
        assertThat(cart.getItems()).containsEntry("p1", 3);
        verify(orders, times(1)).create(any(), anyString(), any());
    }
}
