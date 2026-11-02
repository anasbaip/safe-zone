# Order Service

The Order Service is the Buy01 microservice that handles **shopping carts, checkout and orders**. Customers use it to fill a cart, place a Pay on Delivery order, track it, cancel it and order again. Sellers use it to see the orders that contain their products and move them through delivery.

This document explains:

- where the service fits in the platform, and how a request travels through it
- every concept the service relies on (snapshots, idempotency, stock reservation, optimistic locking, …)
- the data model, the API and the main flows
- what was added to the gateway, Product Service and the frontend to make orders work
- how to run, configure and test it, and what is still missing

| Part | Owner | Where |
|---|---|---|
| Orders: lifecycle, lists, details, cancel, reorder, seller status | Abdelillah | `controller/OrderController`, `service/OrderServiceImpl`, … |
| Cart and Pay on Delivery checkout | Anass | `controller/CartController`, `service/CartService`, `client/OrdersClient` |
| Order pages in the Angular frontend | Abdelillah | `frontend/src/app/features/orders`, `core/services/order.ts` |

---

## Contents

1. [Architecture of the platform](#1-architecture-of-the-platform)
2. [Security: who is the user?](#2-security-who-is-the-user)
3. [Inside the Order Service](#3-inside-the-order-service)
4. [Key concepts](#4-key-concepts)
5. [Data model](#5-data-model)
6. [Order lifecycle](#6-order-lifecycle)
7. [API reference](#7-api-reference)
8. [Main flows step by step](#8-main-flows-step-by-step)
9. [Frontend](#9-frontend)
10. [What was added, service by service](#10-what-was-added-service-by-service)
11. [Configuration and running](#11-configuration-and-running)
12. [Testing and quality gates](#12-testing-and-quality-gates)
13. [Known limitations](#13-known-limitations)
14. [Analytics contract (proposal)](#14-analytics-contract-proposal)

---

## 1. Architecture of the platform

Buy01 is a set of Spring Boot microservices behind one gateway, with an Angular frontend. Each service owns its own MongoDB database; services find each other through Eureka.

```
                         Browser (Angular app)
                                  │  HTTPS
                                  ▼
                    ┌───────────────────────────┐
                    │  frontend (nginx :4200)   │  serves the app,
                    │  /api/** → gateway        │  strips the /api prefix
                    └─────────────┬─────────────┘
                                  ▼
                    ┌───────────────────────────┐
                    │  gateway (:8444 → 8080)   │  JWT check, CORS, CSRF,
                    │  the only published API   │  sets X-User-* headers
                    └─────────────┬─────────────┘
          lb://<service-name>     │  (names resolved through Eureka)
     ┌──────────────┬─────────────┼──────────────┬───────────────┐
     ▼              ▼             ▼              ▼               ▼
 user-service  product-service  media-service  order-service   discovery
   :8081          :8082           :8083          :8084          (Eureka :8761)
     │              │  ▲            │              │  │
     ▼              ▼  │            ▼              ▼  │ GET /products/{id}
 user-mongodb  product-mongodb  media-mongodb  order-mongodb   POST /internal/stock/*
                    │               │              └──────────────┘
                    └──── Kafka ────┘  (product ↔ media image events)
```

| Component | Role for orders |
|---|---|
| **nginx (frontend container)** | Serves the Angular app. Forwards `/api/orders/**` and `/api/cart/**` to the gateway without the `/api` prefix. |
| **Gateway** | Validates the JWT, removes any identity header the client sent, adds trusted `X-User-Id` / `X-User-Roles`, then routes `/orders/**` and `/cart/**` to `lb://order-service`. |
| **Discovery (Eureka)** | Every service registers its name. `lb://order-service` or `http://product-service` resolve to a real container address. |
| **Order Service** | Carts, checkout and orders. Owns `order-mongodb`. |
| **Product Service** | Source of truth for product names, prices, owners and **stock**. The Order Service reads products from it and reserves stock in it. |
| **order-mongodb** | Collections `orders`, `carts`, `checkout_receipts`. |

### How one request travels

`GET /api/orders/my?status=SHIPPED` from a logged-in customer:

```
1. Browser    → GET https://host:4200/api/orders/my?status=SHIPPED   (Authorization: Bearer <jwt>)
2. nginx      → GET https://gateway:8080/orders/my?status=SHIPPED     (prefix /api removed)
3. Gateway    → checks the JWT signature and expiry
              → removes any X-User-Id / X-User-Role / X-User-Roles sent by the client
              → adds X-User-Id: <user id>, X-User-Roles: CLIENT        (from the token)
              → asks Eureka for "order-service" → http://172.18.0.9:8084/orders/my?...
4. Order Service
              → UserContextFilter turns the headers into an AuthenticatedUser
              → OrderController → OrderServiceImpl → OrderSearchRepository (MongoDB)
5. Response   ← JSON page of orders, back through the same chain
```

---

## 2. Security: who is the user?

### The JWT is checked once, at the gateway

The user service issues a JWT at login. Only the gateway verifies it. Behind the gateway, services receive the identity as plain headers:

| Header | Value |
|---|---|
| `X-User-Id` | the user id (JWT subject) |
| `X-User-Roles` | `CLIENT` or `SELLER` |

In the Order Service, `UserContextFilter` reads them and `@CurrentUser AuthenticatedUser user` injects them into controller methods. A request without `X-User-Id` gets **401**.

### Spoofed identity headers are removed

Because services trust these headers, a client must never be able to set them. Before this work, a request **without** a JWT was forwarded unchanged, so anyone could send `X-User-Id: <someone else>` and act as them.

The gateway now, on **every** request:

1. wraps the request in `CustomHttpServletRequestWrapper` and calls `removeHeader` for `X-User-Id`, `X-User-Role` and `X-User-Roles`
2. without a JWT: forwards the request **without** identity
3. with an invalid JWT: returns **401**
4. with a valid JWT: sets the three headers from the token claims

A servlet request can't be modified, so the wrapper **hides** removed headers in all three ways a header can be read: `getHeader`, `getHeaders` and `getHeaderNames`. Header names are compared **case-insensitively** (`TreeMap` / `TreeSet` with `String.CASE_INSENSITIVE_ORDER`), because HTTP header names are case-insensitive: otherwise `x-user-id` would get past a check for `X-User-Id`.

### Other gateway protections

| Protection | Detail |
|---|---|
| CORS | Only `http(s)://localhost:4200`. Allowed request headers: `Authorization`, `Content-Type`, `X-XSRF-TOKEN`, `Idempotency-Key`. `X-User-*` are **not** allowed. |
| CSRF | Cookie-based token (`XSRF-TOKEN` cookie, `X-XSRF-TOKEN` header) on every write request. The Angular `csrf-interceptor` sends it. |
| Roles | `/cart/**` requires the `CLIENT` role at the gateway. Order rules (who may see or change an order) are enforced in the Order Service. |

### Trust boundary

The Order Service trusts the headers because **only the gateway can reach it**: it has no published port and lives on the internal Docker network. The same holds for Product Service's `/internal/stock/**` endpoints, which have no gateway route. See [Known limitations](#13-known-limitations) for what this means if another container is compromised.

---

## 3. Inside the Order Service

```
com.buy01.order_service
├── controller   OrderController (/orders), CartController (/cart)
├── service      OrderServiceImpl, OrderPricing, OrderMapper, CartService
├── repository   OrderRepository, OrderSearchRepository, CartRepository, CheckoutReceiptRepository
├── client       ProductClient (→ Product Service), OrdersClient (checkout → POST /orders)
├── models       Order, OrderItem, OrderStatus, StatusChange, ShippingAddress, PaymentMethod,
│                Cart, CheckoutReceipt
├── dtos         req/ (requests, filters, paging), response/ (orders, pages, cart, errors),
│                external/ (Product Service shapes)
├── security     UserContextFilter, AuthenticatedUser, @CurrentUser + resolver
├── exception    typed exceptions + GlobalExceptionHandler (one error format)
└── config       AppConfig (load-balanced RestClient, Clock), WebConfig, OrderAvailabilityConfig
```

Layering rules:

- **Controllers** only validate input and delegate. No business logic.
- **`OrderServiceImpl`** owns every order rule: roles, ownership, status transitions, stock, idempotency.
- **`OrderPricing`** is the only place money is calculated; **`OrderStatus`** is the only place transitions are defined.
- **Clients** wrap every call to another service and translate its failures into our own exceptions.

---

## 4. Key concepts

Each concept below solves a concrete problem. The problem comes first.

### 4.1 Product snapshot

**Problem:** a seller changes a price or deletes a product after it was ordered. The customer's past order must not change.

**Solution:** when an order is created, each line copies the product's **name, category, image, unit price and seller** (`OrderItem`). The order never reads the product again. Only ids and quantities come from the client; everything else comes from Product Service, so a customer can't choose their own price.

### 4.2 Exact money with `BigDecimal`

**Problem:** `double` can't store most decimal amounts exactly: `0.1 + 0.2 = 0.30000000000000004`.

**Solution:** prices are converted once, at the boundary, with `BigDecimal.valueOf(price)` rounded to 2 decimals (half-up). Every later calculation is exact, and MongoDB stores them as `DECIMAL128`.

| `OrderPricing` method | Rule |
|---|---|
| `toPrice(double)` | 2 decimals, half-up; rejects negative, NaN and infinite |
| `lineTotal(unitPrice, qty)` | `unitPrice × qty` |
| `totalAmount(items)` | sum of line totals |
| `totalItems(items)` | sum of quantities |

### 4.3 State machine for the order status

**Problem:** an order must never go from `DELIVERED` back to `PENDING`, or skip `SHIPPED`.

**Solution:** `OrderStatus.allowedNext()` lists the allowed next statuses (an exhaustive `switch`, so a new status without rules won't compile). Any other change is rejected with **409**. Every change is appended to `statusHistory` (who, when, optional note), which the frontend shows as a timeline. See [Order lifecycle](#6-order-lifecycle).

### 4.4 Idempotency key: one checkout, one order

**Problem:** a double-click, a timeout followed by a retry, or a flaky network can send the same "place order" request twice.

**Solution:** the client generates one key per checkout (`crypto.randomUUID()`) and sends it as the `Idempotency-Key` header on **every retry** of that checkout. The server:

1. looks for an order with the same customer + key, and returns it if found (no second order, no second stock reservation)
2. backs this up with a **unique index** on `{customerId, idempotencyKey}`, for two requests arriving at the same instant: one insert fails with `DuplicateKeyException`, puts back its stock and returns the order created by the other

The cart checkout adds its own layer: a `checkout_receipts` document per customer + key, so a retried checkout returns the same order and finishes clearing the cart.

### 4.5 Optimistic locking with `@Version`

**Problem:** the customer cancels while the seller ships, at the same moment. Without protection, the last save silently wins.

**Solution:** `Order` and `Cart` have a `@Version` field. A save only succeeds if the version hasn't changed since the document was read; the loser gets **409** "The order was modified at the same time, please retry". No database locks are held.

### 4.6 Stock reservation

**Problem:** checking stock is not enough. Two customers can both see "1 available" and both order it.

**Solution:** before saving an order, the Order Service **takes** the units in Product Service, and gives them back on cancellation:

- **Atomic per product.** One conditional update: `{_id: p1, quantity: {$gte: 2}}` → `$inc: {quantity: -2}`. If two orders race for the last unit, only one update matches.
- **All or nothing.** If one product is short, the products already taken in that request are put back before answering **409**.
- **Compensation.** If saving the order fails after the reservation, the stock is released. The release happens only after a cancellation is **saved**, so a failed cancel keeps the stock with the order.

| When | Stock |
|---|---|
| order created | reserved just before the insert |
| reservation fails | nothing saved, 400 to the customer |
| insert fails, or loses an idempotency race | released |
| order cancelled (by the customer, or a seller via `PATCH /status`) | released after the cancellation is saved |
| delivered | nothing to do |

A failed release (Product Service down) doesn't fail the request, because the order's outcome is already decided. It is logged at `ERROR` with product ids and quantities so stock can be fixed by hand.

### 4.7 Seller view: privacy in multi-seller orders

**Problem:** one order can contain products from several sellers. Seller A must not see seller B's products or prices.

**Solution:** each order stores `sellerIds` (indexed). When a seller reads an order, `OrderMapper.toSellerResponse` keeps only **their** items and recalculates `totalItems` / `totalAmount` for them. The address and status history stay visible, because the seller needs them to ship.

### 4.8 Search, filters and paging

Lists are filtered by `status`, a date range (`from` / `to`, UTC days, `to` inclusive) and a text `q` that matches the exact order id **or** part of a product name. They are paginated (`page`, `size`) and newest first.

- The owner condition (`customerId` or `sellerIds`) is **always** part of the query, so filters can't reveal other users' orders.
- The search text goes through `Pattern.quote`, so it is matched literally. Input like `.*` or `(a+)+$` can't match everything or slow MongoDB down (a regular-expression denial of service, "ReDoS").
- Compound indexes `{customerId, createdAt}` and `{sellerIds, createdAt}` serve both the filter and the sort.

### 4.9 Calling another service: discovery, timeouts, error mapping

`ProductClient` uses a `@LoadBalanced` `RestClient`: `http://product-service/...` is resolved through Eureka, with no host or port in the code. It has a **2 s connect / 5 s read timeout**, because Java's HTTP client has none by default and a stuck Product Service would otherwise freeze orders.

Failures are translated so the customer gets a meaningful status:

| Product Service result | Order Service answer |
|---|---|
| 200 with valid data | continue |
| 404 | **400** `Product not found: <id>` (the request must change) |
| 409 on reserve | **400** with Product Service's message (`Not enough stock for …`) |
| other 4xx / 5xx, timeout, invalid body | **502** (a service behind us failed) |

### 4.10 One error format

Every error, from validation to an unexpected exception, has the same JSON shape (see [Errors](#errors)), so the frontend can always show `error.message`. Unexpected errors return a generic message; details stay in the logs.

---

## 5. Data model

The service owns three MongoDB collections in `order-mongodb`.

### `orders`

```json
{
  "_id": ObjectId("6718f2a9c3b1e45d2a9f0c11"),
  "version": 1,
  "customerId": "customer-1",
  "idempotencyKey": "3f2b8c1e-9d4a-4c7e-b1f0-2a6d8e5c7b90",
  "status": "CONFIRMED",
  "items": [
    { "productId": "p1", "sellerId": "seller-a", "productName": "Headphones", "category": "audio",
      "imageUrl": "https://.../h1.png", "unitPrice": NumberDecimal("20.00"),
      "quantity": 2, "lineTotal": NumberDecimal("40.00") },
    { "productId": "p2", "sellerId": "seller-b", "productName": "USB Cable", "category": "accessories",
      "imageUrl": null, "unitPrice": NumberDecimal("5.00"),
      "quantity": 1, "lineTotal": NumberDecimal("5.00") }
  ],
  "sellerIds": ["seller-a", "seller-b"],
  "shippingAddress": { "fullName": "Sara B.", "phone": "0600000000", "street": "1 Main St",
                       "city": "Casablanca", "postalCode": "20000", "country": "Morocco" },
  "paymentMethod": "CASH_ON_DELIVERY",
  "totalItems": 3,
  "totalAmount": NumberDecimal("45.00"),
  "statusHistory": [
    { "status": "PENDING",   "changedAt": ISODate("2026-10-24T10:15:00Z"), "changedBy": "customer-1", "note": "Order placed" },
    { "status": "CONFIRMED", "changedAt": ISODate("2026-10-24T11:02:00Z"), "changedBy": "seller-a",   "note": null }
  ],
  "createdAt": ISODate("2026-10-24T10:15:00Z"),
  "updatedAt": ISODate("2026-10-24T11:02:00Z")
}
```

| Index | Definition | Used by |
|---|---|---|
| `customer_idempotency_key` | `{customerId: 1, idempotencyKey: 1}`, **unique** | duplicate-order protection |
| `customer_created` | `{customerId: 1, createdAt: -1}` | `GET /orders/my` |
| `seller_created` | `{sellerIds: 1, createdAt: -1}` | `GET /orders/seller` |

Indexes are created at startup (`spring.data.mongodb.auto-index-creation=true`, which **must stay on**).

### `carts`

One document per customer; its `_id` **is** the user id, so a customer can't have two carts.

| Field | Meaning |
|---|---|
| `items` | map `productId → quantity` (at most 50 products, 1–100 each) |
| `version` | optimistic locking |
| `pendingKey`, `pendingRequest`, `pendingAddress`, `pendingToken`, `pendingReview` | set while a checkout is in progress; the cart is locked for edits until it completes |

Prices are **not** stored in the cart: they are read from Product Service every time the cart is shown.

### `checkout_receipts`

One document per completed checkout, id = SHA-256 of `userId:idempotencyKey`. It stores the checkout request and the created order, so a retry returns the same order.

---

## 6. Order lifecycle

```
             ┌─────────┐     ┌───────────┐     ┌─────────┐     ┌───────────┐
 placed ───► │ PENDING │ ──► │ CONFIRMED │ ──► │ SHIPPED │ ──► │ DELIVERED │  final
             └─────────┘     └───────────┘     └─────────┘     └───────────┘
                  │                │
                  ▼                ▼
             ┌────────────────────────────┐
             │         CANCELLED          │  final
             └────────────────────────────┘
```

| From | Allowed next | Who |
|---|---|---|
| `PENDING` | `CONFIRMED`, `CANCELLED` | seller; the customer may cancel |
| `CONFIRMED` | `SHIPPED`, `CANCELLED` | seller; the customer may cancel |
| `SHIPPED` | `DELIVERED` | seller |
| `DELIVERED`, `CANCELLED` | none | — |

Skipping a step, going backwards, staying in the same status and changing a final status all return **409**.

---

## 7. API reference

Paths are as seen by the service. From the browser, prefix them with `/api` (`/api/orders/...`, `/api/cart/...`). Every endpoint needs `Authorization: Bearer <jwt>`; amounts are decimal numbers with 2 places; times are ISO-8601 UTC.

### Orders

| Endpoint | Who | Rules | Success |
|---|---|---|---|
| `POST /orders` | `CLIENT` | `Idempotency-Key` header required; products must exist and be in stock | **201**, the order |
| `GET /orders/my` | any user | own orders only; filters and paging | **200**, page |
| `GET /orders/seller` | `SELLER` | orders containing the seller's products; seller view | **200**, page |
| `GET /orders/{id}` | the customer, or a seller in the order | the customer sees everything, a seller only their items | **200** |
| `PATCH /orders/{id}/cancel` | the customer | only from `PENDING` / `CONFIRMED`; stock released | **200** |
| `POST /orders/{id}/reorder` | the customer | any status; returns ids and quantities, **no prices** | **200** |
| `PATCH /orders/{id}/status` | `SELLER` in the order | allowed transition only; optional `note` (≤ 255) | **200**, seller view |

Unknown id → **404**; any other user → **403**; no identity → **401**.

#### `POST /orders`

```http
POST /orders
Idempotency-Key: 3f2b8c1e-9d4a-4c7e-b1f0-2a6d8e5c7b90
```

```json
{
  "items": [
    { "productId": "6718e0c2a1b2c3d4e5f60708", "quantity": 2 },
    { "productId": "6718e0c2a1b2c3d4e5f60709", "quantity": 1 }
  ],
  "shippingAddress": {
    "fullName": "Sara B.", "phone": "0600000000", "street": "1 Main St",
    "city": "Casablanca", "postalCode": "20000", "country": "Morocco"
  },
  "paymentMethod": "CASH_ON_DELIVERY"
}
```

| Field | Rules |
|---|---|
| `items` | 1 to 50 entries; repeated products are merged |
| `items[].quantity` | 1 to 100, and at most 100 per product after merging |
| `shippingAddress.*` | all required; `fullName`, `city`, `country` ≤ 100, `phone` ≤ 30, `street` ≤ 200, `postalCode` ≤ 20 |
| `paymentMethod` | optional, `CASH_ON_DELIVERY` (the only method) |
| `expectedTotal` | optional; the total the customer confirmed. If the current prices give a different total, the order is refused with **409** before any stock is taken |

**201** (`OrderResponse`):

```json
{
  "id": "6718f2a9c3b1e45d2a9f0c11",
  "customerId": "customer-1",
  "status": "PENDING",
  "items": [
    { "productId": "6718e0c2a1b2c3d4e5f60708", "sellerId": "seller-a", "productName": "Headphones",
      "imageUrl": "https://.../h1.png", "unitPrice": 20.00, "quantity": 2, "lineTotal": 40.00 }
  ],
  "shippingAddress": { "fullName": "Sara B.", "phone": "0600000000", "street": "1 Main St",
                       "city": "Casablanca", "postalCode": "20000", "country": "Morocco" },
  "paymentMethod": "CASH_ON_DELIVERY",
  "totalItems": 2,
  "totalAmount": 40.00,
  "statusHistory": [
    { "status": "PENDING", "changedAt": "2026-10-24T10:15:00Z", "changedBy": "customer-1", "note": "Order placed" }
  ],
  "createdAt": "2026-10-24T10:15:00Z",
  "updatedAt": "2026-10-24T10:15:00Z"
}
```

#### `GET /orders/my` and `GET /orders/seller`

| Parameter | Example | Meaning |
|---|---|---|
| `status` | `SHIPPED` | exact status |
| `from` | `2026-01-01` | placed on or after this day (UTC) |
| `to` | `2026-01-31` | placed on or before this day (UTC, whole day included) |
| `q` | `head` | exact order id, or product name containing the text (case-insensitive) |
| `page` | `0` | 0-based, default 0, at most 10000 |
| `size` | `20` | 1 to 100, default 20 |

Unknown `status`, `from` after `to`, `q` over 100 characters or `page` / `size` out of range → **400**.

```json
{
  "content": [ { "id": "6718f2a9c3b1e45d2a9f0c11", "status": "SHIPPED", "...": "OrderResponse fields" } ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3
}
```

#### `POST /orders/{id}/reorder`

Creates nothing. Returns the items so the frontend can put them back in the cart; the new order gets current prices and a fresh stock check.

```json
{ "sourceOrderId": "6718f2a9c3b1e45d2a9f0c11",
  "items": [ { "productId": "6718e0c2a1b2c3d4e5f60708", "quantity": 2 } ] }
```

#### `PATCH /orders/{id}/status`

```json
{ "status": "SHIPPED", "note": "Sent with Amana, tracking 123" }
```

### Cart (`CLIENT` only)

| Endpoint | Body | Effect |
|---|---|---|
| `GET /cart` | — | the cart with **current** prices, availability and a `reviewToken` |
| `POST /cart/items` | `{"productId", "quantity"}` | adds to the quantity already in the cart |
| `PUT /cart/items/{productId}` | `{"quantity"}` | sets the exact quantity (1–100) |
| `DELETE /cart/items/{productId}` | — | removes the product |
| `DELETE /cart` | — | empties the cart |
| `POST /cart/review` | — | checks every item is available; returns a fresh `reviewToken` |
| `POST /cart/checkout` | `{"shippingAddress", "reviewToken"}` + `Idempotency-Key` header | places the order, then clears the cart |

Adding or updating checks stock in Product Service (**409** if not enough). While a checkout is pending, edits return **409**.

### Errors

```json
{
  "timestamp": "2026-10-24T10:15:02",
  "status": 409,
  "error": "Conflict",
  "message": "Cannot change order status from SHIPPED to CANCELLED",
  "path": "/orders/6718f2a9c3b1e45d2a9f0c11/cancel",
  "validationErrors": null
}
```

| Status | When |
|---|---|
| 400 | validation failure (per-field `validationErrors`), malformed JSON, missing `Idempotency-Key`, product not found, not enough stock, quantity limits, bad paging |
| 401 | no `X-User-Id` (not authenticated through the gateway) |
| 403 | wrong role, or not the customer / a seller of this order |
| 404 | order or cart item not found |
| 409 | invalid status transition, concurrent modification, cart changed since review, checkout pending |
| 502 | Product Service (or the order step of checkout) failed |
| 500 | unexpected error, generic message |

---

## 8. Main flows step by step

### 8.1 From cart to order (Pay on Delivery)

```
Customer          Angular                 Order Service                         Product Service
   │ add to cart     │ POST /cart/items        │ CartService.setItem                    │
   │────────────────►│────────────────────────►│── GET /products/{id} (stock) ─────────►│
   │                 │                         │   save cart                            │
   │ checkout        │ POST /cart/review       │ current prices + availability          │
   │────────────────►│────────────────────────►│   → reviewToken (hash of the cart)     │
   │ address +       │ POST /cart/checkout     │ CartService.checkout                   │
   │ confirm         │ Idempotency-Key: K      │ 1. token still matches? else 409       │
   │────────────────►│────────────────────────►│ 2. lock cart (pendingKey = K)          │
   │                 │                         │ 3. OrdersClient → POST /orders (key K) │
   │                 │                         │    OrderServiceImpl.createOrder:       │
   │                 │                         │    snapshot products ─────────────────►│
   │                 │                         │    reserve stock ── /internal/stock ──►│
   │                 │                         │    insert order (PENDING)              │
   │                 │                         │ 4. verify the returned order           │
   │                 │                         │ 5. save checkout receipt               │
   │                 │                         │ 6. clear cart, unlock                  │
   │  "Order placed" │◄────────────────────────│ 201 order                              │
```

If anything fails after step 2, the cart stays locked with the same key, and the frontend retries with **the same `Idempotency-Key`**: the order step returns the existing order instead of creating a second one.

If the order step **refuses** the checkout for good (a 4xx, for example prices changed since the review so `expectedTotal` no longer matches, or a product sold out), `OrdersClient` raises `OrderRejectedException`, the cart is unlocked with its items kept, and the customer gets **409** and reviews the cart again.

### 8.2 Creating an order (`OrderServiceImpl.createOrder`)

```
CLIENT? ──no──► 403
   │yes
key already used? ──yes──► existing order
   │no
merge items (>100 per product ──► 400)
   │
for each product: fetch → validate → stock check → freeze name/price/seller/image
   │   (not found / not enough stock ──► 400, upstream problem ──► 502)
build order (PENDING, totals, first history entry "Order placed")
   │
reserve stock ──not enough──► 400 (nothing saved, nothing taken)
   │
insert ──duplicate key──► release + return the order created by the concurrent request
   │   ──other failure──► release + error
   ▼
201 + order
```

### 8.3 Cancel

```
customer? ──no──► 403
status PENDING or CONFIRMED? ──no──► 409
append CANCELLED to history → save (@Version) ──conflict──► 409, stock untouched
release stock (failure logged, not returned)
200 + cancelled order
```

### 8.4 Reorder

`POST /orders/{id}/reorder` returns `{productId, quantity}` pairs. The frontend adds them to the cart with `POST /cart/items`; products that no longer exist or are out of stock are reported to the customer. The new checkout prices them again.

### 8.5 Seller status update

```
seller with products in the order? ──no──► 403
allowed transition? ──no──► 409
append to history (with optional note) → save
CANCELLED? → release stock
200 + seller view
```

---

## 9. Frontend

The Angular pages for orders are documented in detail in [`frontend/src/app/features/orders/README.md`](../frontend/src/app/features/orders/README.md). In short:

| Route | Who | Page |
|---|---|---|
| `/orders` | customer | order history: search, status and date filters, paging |
| `/seller/orders` | seller | orders containing the seller's products, same filters |
| `/orders/:id` | customer or seller | products and prices at order time, totals, address, status timeline |

| Action | Where | Calls |
|---|---|---|
| Cancel (with confirmation) | details page, customer, `PENDING` / `CONFIRMED` only | `PATCH /api/orders/{id}/cancel` |
| Order again | details page, customer | `POST /api/orders/{id}/reorder`, then `POST /api/cart/items` per product |
| Update status (+ optional note) | details page, seller, valid next statuses only | `PATCH /api/orders/{id}/status` |
| View order | checkout confirmation | link to `/orders/{id}` |

- **`core/models/order.ts`** holds the types and a copy of the transition table (`NEXT_STATUSES`, `CANCELLABLE`), so the UI only offers actions the server will accept. The server still checks everything.
- **`core/services/order.ts`** (`OrderService`) wraps every endpoint; the existing interceptors add the JWT and CSRF headers.
- One list component and one details component serve both roles, which keeps duplicated code low for the Sonar gate.

The UI was delivered as stacked pull requests: `orders-ui-api` (#8), `orders-ui-list` (#10), `orders-ui-details`, `orders-ui-actions`, `orders-ui-seller-status`.

---

## 10. What was added, service by service

### Order Service

| Area | Files |
|---|---|
| Module setup | Spring Boot app, MongoDB, Eureka client, Dockerfile, Jenkins and Sonar integration |
| API | `OrderController`, request/response DTOs, `PageParams`, `PageResponse`, `OrderFilter` |
| Business logic | `OrderServiceImpl`, `OrderPricing`, `OrderMapper`, `OrderStatus.allowedNext()` |
| Persistence | `Order` (indexes, `@Version`), `OrderRepository`, `OrderSearchRepository` (`MongoTemplate` queries) |
| Product integration | `ProductClient` (snapshot, reserve, release), `AppConfig` (load-balanced `RestClient` with timeouts, `Clock`) |
| Identity | `UserContextFilter`, `AuthenticatedUser`, `@CurrentUser` |
| Errors | typed exceptions, `GlobalExceptionHandler` (incl. 409 for optimistic locking, 502 for upstream) |
| Cart and checkout (Anass) | `CartController`, `CartService`, `Cart`, `CheckoutReceipt`, `OrdersClient`, `OrderAvailabilityConfig` |

### Product Service

| File | Change |
|---|---|
| `controller/StockController.java` | **new**: `POST /internal/stock/reserve` and `/internal/stock/release` |
| `service/StockService.java` | **new**: atomic, all-or-nothing decrement; release |
| `dtos/req/StockRequest.java`, `exception/ConflictException.java` | **new**, with a 409 handler in `GlobalExceptionHandler` |

### Gateway

| File | Change |
|---|---|
| `security/JwtAuthenticationFilter.java` | always removes client-sent `X-User-*`, then sets them only from a valid JWT |
| `filter/CustomHttpServletRequestWrapper.java` | case-insensitive header names; `removeHeader` hides headers in every accessor |
| `security/SecurityConfig.java` | CORS allows `Idempotency-Key`, no longer `X-User-*`; `/cart/**` requires `CLIENT` |
| `application.properties` | routes `/orders/**`, `/cart/**`, `/api/cart/**` → `lb://order-service` |

### Frontend

See [Frontend](#9-frontend).

### Infrastructure

| File | Change |
|---|---|
| `docker-compose.yml` | `order-service` and `order-mongodb` containers, `PRODUCT_SERVICE_URL`, `ORDERS_SERVICE_URL` |
| `Jenkinsfile`, `.github/workflows/quality.yml`, `.ci/sonar_ci.py` | order-service built, tested and scanned like the other services |

---

## 11. Configuration and running

### Properties

| Property | Default | Environment variable | Notes |
|---|---|---|---|
| `server.port` | `8084` | `SERVER_PORT` | |
| `spring.application.name` | `order-service` | | Eureka name; the gateway routes depend on it |
| `spring.mongodb.uri` | `mongodb://localhost:27021/order_service_db` | `MONGODB_URI` | Compose also sets `SPRING_MONGODB_URI` |
| `spring.data.mongodb.auto-index-creation` | `true` | | **keep `true`**: idempotency relies on the unique index |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka/` | `EUREKA_CLIENT_SERVICE_URL` | |
| `product-service.base-url` | `http://product-service` | `PRODUCT_SERVICE_URL` | an Eureka name, without `/products` |
| `orders-service.base-url` | `http://localhost:8084/orders` | `ORDERS_SERVICE_URL` | where checkout creates orders; `http://order-service:8084/orders` in Compose |

`AppConfig` beans: `loadBalancedRestClientBuilder` (2 s connect, 5 s read timeout) and `clock` (`Clock.systemUTC()`, replaced by a fixed clock in tests).

### Full platform

The whole platform is deployed by the Jenkins pipeline described in the root [README](../README.md): it builds one image per service (`order-service:${APP_IMAGE_TAG}`, …) and starts them with the root `docker-compose.yml`. The app is then at `https://localhost:4200` and the gateway at `https://localhost:8444`. The Order Service has no published port; its database is bound to `127.0.0.1:27021` for local debugging only.

### Order Service alone, for development

Start a MongoDB on port 27021 (for example `docker compose up -d order-mongodb` with the `.env` file filled in), a Eureka server and Product Service, then:

```bash
cd order-service
./mvnw spring-boot:run
```

Requests sent directly to `localhost:8084` must carry `X-User-Id` and `X-User-Roles` themselves, since there is no gateway in between:

```bash
curl -H 'X-User-Id: customer-1' -H 'X-User-Roles: CLIENT' http://localhost:8084/orders/my
```

---

## 12. Testing and quality gates

```bash
cd order-service && ./mvnw clean verify     # backend tests + JaCoCo report (target/site/jacoco)
cd frontend && npm run test:coverage         # frontend tests + coverage
```

| Module | Tests | Notes |
|---|---|---|
| order-service | 184 | orders, cart, checkout, clients, security, DTOs, and 5 integration tests on a real MongoDB |
| product-service | 85 | includes `StockServiceTest`, `StockControllerTest` |
| gateway | 27 | includes header-spoofing tests in `JwtAuthenticationFilterTest` and `CustomHttpServletRequestWrapperTest` |
| frontend | 199 | includes the order model, service, list and details specs |

Main order-service test classes:

| Class | Covers |
|---|---|
| `OrderServiceImplTest` | creation (snapshot, totals, merging, idempotency and its race, stock reservation and release), lists, access rules, cancel, reorder, status updates |
| `OrderControllerTest` | routing, validation, paging, auth, every error status (MockMvc) |
| `OrderStatusTest` | every allowed and forbidden transition |
| `OrderPricingTest` | rounding, floating-point safety, invalid prices |
| `OrderSearchRepositoryTest` | the generated Mongo query for each filter, literal regex, paging |
| `ProductClientTest` | product lookup and stock calls: success, 404, 409, 5xx, unreachable, empty body |
| `CartServiceTest`, `CartControllerTest`, `OrdersClientTest` | cart rules, checkout, idempotent retry, order verification |
| `OrderFlowIntegrationTest` | **integration**: HTTP → controller → service → MongoDB 7 (Testcontainers). Create and read back, one order per idempotency key (real unique index), seller view and 403, cancellation saved with history and version, filters and paging run in the database |

Unit tests need no MongoDB, Eureka or other service: repositories are mocked, HTTP calls use `MockRestServiceServer`, and time is fixed with `Clock.fixed(...)`.

`OrderFlowIntegrationTest` starts a throw-away `mongo:7` container with Testcontainers (`@ServiceConnection` points the app at it) and replaces only `ProductClient`. It needs Docker; on a machine without Docker it is **skipped**, not failed (`@Testcontainers(disabledWithoutDocker = true)`).

### CI quality gate

GitHub Actions (and Jenkins) build every service, then scan each one with SonarQube. The "Buy01 Quality Gate" fails a service when its **new code** has:

| Condition | Fails when |
|---|---|
| issues (bugs, vulnerabilities, code smells) | more than 0 |
| coverage | below 80 % |
| duplicated lines | above 3 % |
| security hotspots reviewed | below 100 % |

The frontend build also enforces 80 % statements, branches, functions and lines. Example of a fixed finding: rule `java:S5778` requires an `assertThatThrownBy` lambda to contain only the call expected to throw, so test arguments are built before the assertion.

---

## 13. Known limitations

| Limitation | Impact | Possible follow-up |
|---|---|---|
| Stock is reserved before the order is saved, **not in one transaction** | a crash between the two steps, or a failed release, leaves units taken (logged at `ERROR`) | outbox pattern or event-based reservation |
| Services **trust `X-User-*` from anyone on the Docker network** | another compromised container could act as any user, or call `/internal/stock` | a shared secret between gateway and services, or JWT verification in each service |
| Checkout creates the order over HTTP **inside the same service** (`OrdersClient` → `/orders`) | an extra network hop that passes identity headers itself | call `OrderService` directly when both live in one service |
| A seller editing a product sets `quantity` directly | an edit at the same moment as an order can overwrite that order's decrement | change stock by delta |
| **One status per order** | in a multi-seller order, any of its sellers changes the status for everyone | status per seller or per item |
| No **system** role for status changes | only sellers move orders forward | trusted internal events (e.g. Kafka) |
| Date filters use **UTC days** | an order at 00:30 Morocco time can appear under the previous day | accept the client's time zone |
| No `product-service` registered in Eureka → **500** instead of 502 | wrong status while Product Service is starting | map `IllegalStateException` in `ProductClient` |

---

## 14. Analytics contract

Analytics are implemented in the Order Service, which owns the source collection. Public calls go through the existing gateway `/api/orders/**` route and JWT-derived identity headers.

| Endpoint | Role | Scope |
| --- | --- | --- |
| `GET /api/orders/analytics/me` | CLIENT | The authenticated customer's orders |
| `GET /api/orders/analytics/seller` | SELLER | Only the authenticated seller's lines in related orders |

### Financial and date rules

- Only `DELIVERED` orders contribute to **all** amounts, order counts, unit counts, rankings and trends. Cancelled, pending, confirmed and shipped orders are excluded. This is the implementation's Pay on Delivery revenue policy, subject to team review; there is no payment ledger.
- Filters use `createdAt` (placement date) in UTC, not delivery date. `from`/`to` are inclusive ISO dates.
- Default: first day of the month 11 months before today through today. Either date can be omitted: `to` defaults to today; `from` defaults relative to the effective end date.
- Ranges may include at most 366 days and must stay between years 1970 and 9998. Invalid dates/ranges return 400. Unauthenticated requests return 401; wrong roles return 403.
- Seller totals use only matching `items.sellerId` lines, never the order's overall total. Order counts count distinct orders, not lines.
- Amounts use historical `lineTotal` snapshots and remain Decimal128/BigDecimal. Names and categories come from snapshots, even when live products have changed or been deleted.
- Legacy missing/blank categories become `uncategorized`. Category snapshots are not backfilled from current products.
- Product/category rankings return the top five by units descending, amount descending, then ID/category ascending. Product labels use the latest matching order snapshot.
- Monthly trends use `YYYY-MM` in UTC, include zero-valued months, and expose order count, units and amount.

### Response

Both endpoints share this response. `totalAmount` represents customer spending or seller revenue according to the endpoint. No addresses, customer identities or other sellers' lines are returned.

```json
{
  "from": "2026-01-01",
  "to": "2026-03-31",
  "countedStatus": "DELIVERED",
  "totalAmount": 40.00,
  "orderCount": 1,
  "totalUnits": 2,
  "products": [{ "productId": "p1", "name": "Headphones", "units": 2, "amount": 40.00 }],
  "categories": [{ "category": "audio", "units": 2, "amount": 40.00 }],
  "months": [
    { "month": "2026-01", "orderCount": 1, "units": 2, "amount": 40.00 },
    { "month": "2026-02", "orderCount": 0, "units": 0, "amount": 0 },
    { "month": "2026-03", "orderCount": 0, "units": 0, "amount": 0 }
  ]
}
```

### Implementation and verification

`AnalyticsController` → `AnalyticsService` (roles, defaults, date bounds) → `AnalyticsRepository` (MongoDB aggregation).

The pipeline matches indexed ownership/date fields and delivered status before unwinding items, then restricts seller lines and uses `$facet` for totals, products, categories and monthly trends. It aggregates the full matching data, independent of order-list pagination. Existing `customer_created` and `seller_created` indexes support the initial match; inspect query plans with representative production volumes before adding further indexes.

`AnalyticsIntegrationTest` uses real MongoDB through Testcontainers to cover decimal sums, status/owner/date exclusions, mixed-seller orders, distinct counts, category fallback, empty results and top-five ordering. `AnalyticsControllerTest` checks authentication, role separation, identity scoping, default dates and invalid ranges. Docker must be available to run integration tests; Testcontainers skips them automatically where Docker is unavailable.

The Angular `AnalyticsDashboard` is embedded in `/profile` and the seller dashboard. It provides date controls, cards, product tables, category bars and monthly charts with readable values, loading/error/retry and empty states. API and component tests cover customer/seller scopes, date validation, cancellation of stale requests and rendering. The display uses the existing order pages' USD convention.
