package com.buy01.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import com.buy01.order_service.client.ProductClient;
import com.buy01.order_service.dtos.common.ShippingAddressDto;
import com.buy01.order_service.dtos.external.ProductDetails;
import com.buy01.order_service.dtos.req.CreateOrderRequest;
import com.buy01.order_service.dtos.req.OrderFilter;
import com.buy01.order_service.dtos.req.OrderItemRequest;
import com.buy01.order_service.dtos.req.UpdateOrderStatusRequest;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.exception.BadRequestException;
import com.buy01.order_service.exception.ConflictException;
import com.buy01.order_service.exception.ForbiddenException;
import com.buy01.order_service.exception.ResourceNotFoundException;
import com.buy01.order_service.exception.UpstreamServiceException;
import com.buy01.order_service.models.Order;
import com.buy01.order_service.models.OrderItem;
import com.buy01.order_service.models.OrderStatus;
import com.buy01.order_service.models.PaymentMethod;
import com.buy01.order_service.models.ShippingAddress;
import com.buy01.order_service.models.StatusChange;
import com.buy01.order_service.repository.OrderRepository;
import com.buy01.order_service.repository.OrderSearchRepository;
import com.buy01.order_service.security.AuthenticatedUser;

class OrderServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String KEY = "checkout-key-1";

    private static final AuthenticatedUser CUSTOMER = new AuthenticatedUser("customer-1", List.of("CLIENT"));
    private static final AuthenticatedUser OTHER_CUSTOMER = new AuthenticatedUser("customer-2", List.of("CLIENT"));
    private static final AuthenticatedUser SELLER_A = new AuthenticatedUser("seller-a", List.of("SELLER"));
    private static final AuthenticatedUser SELLER_B = new AuthenticatedUser("seller-b", List.of("SELLER"));
    private static final AuthenticatedUser OTHER_SELLER = new AuthenticatedUser("seller-z", List.of("SELLER"));

    private static final ShippingAddressDto ADDRESS =
            new ShippingAddressDto("Jane Doe", "0600000000", "1 Main St", "Casablanca", "20000", "Morocco");

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderSearchRepository orderSearchRepository = mock(OrderSearchRepository.class);
    private final ProductClient productClient = mock(ProductClient.class);
    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orderRepository, orderSearchRepository, productClient, new OrderMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(orderRepository.insert(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId("new-order");
            return order;
        });
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    class CreateOrder {

        @BeforeEach
        void products() {
            when(productClient.getProduct("headphones"))
                    .thenReturn(new ProductDetails("headphones", "Headphones", 19.99, "seller-a",
                            List.of("https://img/h1.png", "https://img/h2.png"), 10));
            when(productClient.getProduct("cable"))
                    .thenReturn(new ProductDetails("cable", "USB Cable", 4.5, "seller-b", null, 50));
            when(orderRepository.findByCustomerIdAndIdempotencyKey("customer-1", KEY)).thenReturn(Optional.empty());
        }

        @Test
        void snapshotsProductsAndComputesTotals() {
            OrderResponse response = service.createOrder(
                    request(new OrderItemRequest("headphones", 2), new OrderItemRequest("cable", 3)), KEY, CUSTOMER);

            assertThat(response.id()).isEqualTo("new-order");
            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(response.customerId()).isEqualTo("customer-1");
            assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.CASH_ON_DELIVERY);
            assertThat(response.totalItems()).isEqualTo(5);
            assertThat(response.totalAmount()).isEqualTo(new BigDecimal("53.48"));
            assertThat(response.items()).satisfiesExactly(
                    headphones -> {
                        assertThat(headphones.productName()).isEqualTo("Headphones");
                        assertThat(headphones.sellerId()).isEqualTo("seller-a");
                        assertThat(headphones.imageUrl()).isEqualTo("https://img/h1.png");
                        assertThat(headphones.unitPrice()).isEqualTo(new BigDecimal("19.99"));
                        assertThat(headphones.lineTotal()).isEqualTo(new BigDecimal("39.98"));
                    },
                    cable -> {
                        assertThat(cable.imageUrl()).isNull();
                        assertThat(cable.unitPrice()).isEqualTo(new BigDecimal("4.50"));
                        assertThat(cable.lineTotal()).isEqualTo(new BigDecimal("13.50"));
                    });
            assertThat(response.statusHistory()).singleElement()
                    .satisfies(change -> {
                        assertThat(change.status()).isEqualTo(OrderStatus.PENDING);
                        assertThat(change.changedAt()).isEqualTo(NOW);
                        assertThat(change.changedBy()).isEqualTo("customer-1");
                    });
            assertThat(response.createdAt()).isEqualTo(NOW);
        }

        @Test
        void storesIdempotencyKeySellersAndAddress() {
            service.createOrder(request(new OrderItemRequest("headphones", 1), new OrderItemRequest("cable", 1)),
                    KEY, CUSTOMER);

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).insert(saved.capture());
            assertThat(saved.getValue().getIdempotencyKey()).isEqualTo(KEY);
            assertThat(saved.getValue().getSellerIds()).containsExactly("seller-a", "seller-b");
            assertThat(saved.getValue().getShippingAddress())
                    .isEqualTo(new ShippingAddress("Jane Doe", "0600000000", "1 Main St", "Casablanca", "20000",
                            "Morocco"));
        }

        @Test
        void mergesTheSameProductListedTwice() {
            OrderResponse response = service.createOrder(
                    request(new OrderItemRequest("headphones", 1), new OrderItemRequest("headphones", 2)), KEY,
                    CUSTOMER);

            assertThat(response.items()).singleElement()
                    .satisfies(item -> assertThat(item.quantity()).isEqualTo(3));
            verify(productClient).getProduct("headphones");
        }

        @Test
        void sellerIdsAreDistinct() {
            when(productClient.getProduct("case"))
                    .thenReturn(new ProductDetails("case", "Case", 5, "seller-a", List.of(), 5));

            service.createOrder(request(new OrderItemRequest("headphones", 1), new OrderItemRequest("case", 1)),
                    KEY, CUSTOMER);

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).insert(saved.capture());
            assertThat(saved.getValue().getSellerIds()).containsExactly("seller-a");
        }

        @Test
        void keepsRequestedPaymentMethod() {
            var request = new CreateOrderRequest(List.of(new OrderItemRequest("cable", 1)), ADDRESS,
                    PaymentMethod.CASH_ON_DELIVERY);

            assertThat(service.createOrder(request, KEY, CUSTOMER).paymentMethod())
                    .isEqualTo(PaymentMethod.CASH_ON_DELIVERY);
        }

        @Test
        void sameIdempotencyKey_returnsExistingOrderWithoutCreatingAnother() {
            Order existing = order(OrderStatus.CONFIRMED);
            when(orderRepository.findByCustomerIdAndIdempotencyKey("customer-1", KEY))
                    .thenReturn(Optional.of(existing));

            OrderResponse response = service.createOrder(request(new OrderItemRequest("headphones", 1)), KEY,
                    CUSTOMER);

            assertThat(response.id()).isEqualTo("order-1");
            verify(orderRepository, never()).insert(any(Order.class));
            verifyNoInteractions(productClient);
        }

        @Test
        void concurrentDuplicate_returnsTheOrderThatWon() {
            Order winner = order(OrderStatus.PENDING);
            when(orderRepository.findByCustomerIdAndIdempotencyKey("customer-1", KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));
            when(orderRepository.insert(any(Order.class))).thenThrow(new DuplicateKeyException("duplicate"));

            OrderResponse response = service.createOrder(request(new OrderItemRequest("headphones", 1)), KEY,
                    CUSTOMER);

            assertThat(response.id()).isEqualTo("order-1");
        }

        @Test
        void duplicateKeyWithoutExistingOrder_isRethrown() {
            when(orderRepository.insert(any(Order.class))).thenThrow(new DuplicateKeyException("duplicate"));
            var request = request(new OrderItemRequest("headphones", 1));

            assertThatThrownBy(() -> service.createOrder(request, KEY, CUSTOMER))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        void sellerCannotPlaceOrders() {
            var request = request(new OrderItemRequest("headphones", 1));

            assertThatThrownBy(() -> service.createOrder(request, KEY, SELLER_A))
                    .isInstanceOf(ForbiddenException.class);
            verifyNoInteractions(productClient);
        }

        @Test
        void rejectsQuantityAboveStock() {
            var request = request(new OrderItemRequest("headphones", 11));

            assertThatThrownBy(() -> service.createOrder(request, KEY, CUSTOMER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Not enough stock for Headphones: 10 available");
            verify(orderRepository, never()).insert(any(Order.class));
        }

        @Test
        void rejectsMergedQuantityAboveLimit() {
            var request = request(new OrderItemRequest("cable", 60), new OrderItemRequest("cable", 41));

            assertThatThrownBy(() -> service.createOrder(request, KEY, CUSTOMER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("at most 100");
            verifyNoInteractions(productClient);
        }

        @Test
        void rejectsIncompleteProductData() {
            when(productClient.getProduct("broken"))
                    .thenReturn(new ProductDetails("broken", null, 1, "seller-a", List.of(), 5));
            var request = request(new OrderItemRequest("broken", 1));

            assertThatThrownBy(() -> service.createOrder(request, KEY, CUSTOMER))
                    .isInstanceOf(UpstreamServiceException.class);
        }

        @Test
        void rejectsProductWithoutSeller() {
            when(productClient.getProduct("orphan"))
                    .thenReturn(new ProductDetails("orphan", "Orphan", 1, null, List.of(), 5));
            var request = request(new OrderItemRequest("orphan", 1));

            assertThatThrownBy(() -> service.createOrder(request, KEY, CUSTOMER))
                    .isInstanceOf(UpstreamServiceException.class);
        }

        @Test
        void rejectsInvalidProductPrice() {
            when(productClient.getProduct("negative"))
                    .thenReturn(new ProductDetails("negative", "Negative", -1, "seller-a", List.of(), 5));
            var request = request(new OrderItemRequest("negative", 1));

            assertThatThrownBy(() -> service.createOrder(request, KEY, CUSTOMER))
                    .isInstanceOf(UpstreamServiceException.class)
                    .hasMessageContaining("invalid price");
        }

        private CreateOrderRequest request(OrderItemRequest... items) {
            return new CreateOrderRequest(List.of(items), ADDRESS, null);
        }
    }

    @Nested
    class Lists {

        @Test
        void getMyOrders_returnsCustomerOrders() {
            when(orderSearchRepository.findCustomerOrders("customer-1", OrderFilter.none()))
                    .thenReturn(List.of(order(OrderStatus.PENDING)));

            assertThat(service.getMyOrders(OrderFilter.none(), CUSTOMER))
                    .singleElement()
                    .satisfies(response -> assertThat(response.items()).hasSize(2));
        }

        @Test
        void getSellerOrders_showsOnlyTheSellersItemsAndTotals() {
            var filter = new OrderFilter(OrderStatus.PENDING, null, null, null);
            when(orderSearchRepository.findSellerOrders("seller-a", filter))
                    .thenReturn(List.of(order(OrderStatus.PENDING)));

            assertThat(service.getSellerOrders(filter, SELLER_A)).singleElement().satisfies(response -> {
                assertThat(response.items()).extracting("sellerId").containsOnly("seller-a");
                assertThat(response.totalItems()).isEqualTo(2);
                assertThat(response.totalAmount()).isEqualTo(new BigDecimal("40.00"));
            });
        }

        @Test
        void getSellerOrders_requiresSellerRole() {
            var filter = OrderFilter.none();

            assertThatThrownBy(() -> service.getSellerOrders(filter, CUSTOMER))
                    .isInstanceOf(ForbiddenException.class);
            verifyNoInteractions(orderSearchRepository);
        }
    }

    @Nested
    class GetOrder {

        @Test
        void customerSeesTheFullOrder() {
            stored(order(OrderStatus.PENDING));

            OrderResponse response = service.getOrder("order-1", CUSTOMER);

            assertThat(response.items()).hasSize(2);
            assertThat(response.totalAmount()).isEqualTo(new BigDecimal("45.00"));
        }

        @Test
        void sellerSeesOnlyTheirItems() {
            stored(order(OrderStatus.PENDING));

            OrderResponse response = service.getOrder("order-1", SELLER_B);

            assertThat(response.items()).singleElement()
                    .satisfies(item -> assertThat(item.sellerId()).isEqualTo("seller-b"));
            assertThat(response.totalAmount()).isEqualTo(new BigDecimal("5.00"));
        }

        @Test
        void otherCustomerIsForbidden() {
            stored(order(OrderStatus.PENDING));

            assertThatThrownBy(() -> service.getOrder("order-1", OTHER_CUSTOMER))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void sellerWithoutProductsInOrderIsForbidden() {
            stored(order(OrderStatus.PENDING));

            assertThatThrownBy(() -> service.getOrder("order-1", OTHER_SELLER))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void sellerIdWithoutSellerRoleIsForbidden() {
            stored(order(OrderStatus.PENDING));
            var noRole = new AuthenticatedUser("seller-a", List.of("CLIENT"));

            assertThatThrownBy(() -> service.getOrder("order-1", noRole))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void missingOrderIsNotFound() {
            when(orderRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getOrder("missing", CUSTOMER))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Order not found: missing");
        }
    }

    @Nested
    class Cancel {

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"PENDING", "CONFIRMED"})
        void customerCanCancelBeforeShipping(OrderStatus status) {
            stored(order(status));

            OrderResponse response = service.cancelOrder("order-1", CUSTOMER);

            assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(response.updatedAt()).isEqualTo(NOW);
            assertThat(response.statusHistory()).last().satisfies(change -> {
                assertThat(change.status()).isEqualTo(OrderStatus.CANCELLED);
                assertThat(change.changedBy()).isEqualTo("customer-1");
                assertThat(change.note()).isEqualTo("Cancelled by customer");
            });
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "DELIVERED", "CANCELLED"})
        void cannotCancelAfterShippingOrTwice(OrderStatus status) {
            stored(order(status));

            assertThatThrownBy(() -> service.cancelOrder("order-1", CUSTOMER))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Cannot change order status from " + status + " to CANCELLED");
            verify(orderRepository, never()).save(any(Order.class));
        }

        @Test
        void onlyTheCustomerCanCancel() {
            stored(order(OrderStatus.PENDING));

            assertThatThrownBy(() -> service.cancelOrder("order-1", OTHER_CUSTOMER))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.cancelOrder("order-1", SELLER_A))
                    .isInstanceOf(ForbiddenException.class);
            verify(orderRepository, never()).save(any(Order.class));
        }
    }

    @Nested
    class Reorder {

        @Test
        void returnsProductsAndQuantitiesOfTheOrder() {
            stored(order(OrderStatus.DELIVERED));

            var response = service.reorder("order-1", CUSTOMER);

            assertThat(response.sourceOrderId()).isEqualTo("order-1");
            assertThat(response.items()).containsExactly(
                    new OrderItemRequest("headphones", 2), new OrderItemRequest("cable", 1));
        }

        @Test
        void worksForCancelledOrdersToo() {
            stored(order(OrderStatus.CANCELLED));

            assertThat(service.reorder("order-1", CUSTOMER).items()).hasSize(2);
        }

        @Test
        void onlyTheCustomerCanReorder() {
            stored(order(OrderStatus.DELIVERED));

            assertThatThrownBy(() -> service.reorder("order-1", OTHER_CUSTOMER))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    class UpdateStatus {

        @Test
        void sellerMovesOrderForward() {
            stored(order(OrderStatus.CONFIRMED));

            OrderResponse response = service.updateStatus("order-1",
                    new UpdateOrderStatusRequest(OrderStatus.SHIPPED, "Sent with DHL"), SELLER_A);

            assertThat(response.status()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(response.items()).extracting("sellerId").containsOnly("seller-a");
            assertThat(response.statusHistory()).hasSize(2).last().satisfies(change -> {
                assertThat(change.changedBy()).isEqualTo("seller-a");
                assertThat(change.note()).isEqualTo("Sent with DHL");
                assertThat(change.changedAt()).isEqualTo(NOW);
            });
            verify(orderRepository).save(any(Order.class));
        }

        @Test
        void sellerCanCancelPendingOrder() {
            stored(order(OrderStatus.PENDING));

            OrderResponse response = service.updateStatus("order-1",
                    new UpdateOrderStatusRequest(OrderStatus.CANCELLED, "Out of stock"), SELLER_B);

            assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        void invalidTransitionIsConflict() {
            stored(order(OrderStatus.PENDING));
            var request = new UpdateOrderStatusRequest(OrderStatus.DELIVERED, null);

            assertThatThrownBy(() -> service.updateStatus("order-1", request, SELLER_A))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Cannot change order status from PENDING to DELIVERED");
            verify(orderRepository, never()).save(any(Order.class));
        }

        @Test
        void customerCannotChangeStatus() {
            stored(order(OrderStatus.PENDING));
            var request = new UpdateOrderStatusRequest(OrderStatus.CONFIRMED, null);

            assertThatThrownBy(() -> service.updateStatus("order-1", request, CUSTOMER))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void sellerWithoutProductsInOrderCannotChangeStatus() {
            stored(order(OrderStatus.PENDING));
            var request = new UpdateOrderStatusRequest(OrderStatus.CONFIRMED, null);

            assertThatThrownBy(() -> service.updateStatus("order-1", request, OTHER_SELLER))
                    .isInstanceOf(ForbiddenException.class);
            verify(orderRepository, never()).save(any(Order.class));
        }
    }

    // ---------- helpers ----------

    private void stored(Order order) {
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    }

    /** Customer-1 bought 2 headphones from seller-a (40.00) and 1 cable from seller-b (5.00). */
    private static Order order(OrderStatus status) {
        Instant placed = Instant.parse("2026-02-01T09:00:00Z");
        List<OrderItem> items = List.of(
                new OrderItem("headphones", "seller-a", "Headphones", null, new BigDecimal("20.00"), 2,
                        new BigDecimal("40.00")),
                new OrderItem("cable", "seller-b", "USB Cable", null, new BigDecimal("5.00"), 1,
                        new BigDecimal("5.00")));
        return Order.builder()
                .id("order-1")
                .customerId("customer-1")
                .idempotencyKey(KEY)
                .status(status)
                .items(items)
                .sellerIds(List.of("seller-a", "seller-b"))
                .shippingAddress(new ShippingAddress("Jane Doe", "0600000000", "1 Main St", "Casablanca", "20000",
                        "Morocco"))
                .paymentMethod(PaymentMethod.CASH_ON_DELIVERY)
                .totalItems(3)
                .totalAmount(new BigDecimal("45.00"))
                .statusHistory(new ArrayList<>(List.of(new StatusChange(OrderStatus.PENDING, placed, "customer-1",
                        "Order placed"))))
                .createdAt(placed)
                .updatedAt(placed)
                .build();
    }
}
