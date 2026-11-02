# Orders UI

The Angular pages that let **customers** follow, cancel and repeat their orders, and let **sellers** manage the orders that contain their products. They talk to the Order Service; its rules, API and architecture are described in [`order-service/README.md`](../../../../../order-service/README.md).

---

## Contents

1. [What users can do](#1-what-users-can-do)
2. [Pages and routes](#2-pages-and-routes)
3. [File structure](#3-file-structure)
4. [How the code is organised](#4-how-the-code-is-organised)
5. [Order list page](#5-order-list-page)
6. [Order details page](#6-order-details-page)
7. [Customer actions: cancel and order again](#7-customer-actions-cancel-and-order-again)
8. [Seller action: update the status](#8-seller-action-update-the-status)
9. [Feedback: loading, errors, toasts](#9-feedback-loading-errors-toasts)
10. [Security in the UI](#10-security-in-the-ui)
11. [Responsive layout and accessibility](#11-responsive-layout-and-accessibility)
12. [Tests](#12-tests)
13. [Pull requests](#13-pull-requests)
14. [Limitations](#14-limitations)

---

## 1. What users can do

| Customer (`CLIENT`) | Seller (`SELLER`) |
|---|---|
| See their order history, newest first | See every order that contains their products |
| Search by order reference or product name | Same search |
| Filter by status and by date range | Same filters |
| Open an order: products, prices, totals, address, status timeline | Open an order: **only their own products** and totals |
| Cancel an order while it is pending or confirmed | Move the order to its next status, with an optional note |
| Order the same products again (added to the cart) | — |
| Open a new order straight from the checkout confirmation | — |

---

## 2. Pages and routes

| Route | Page | Who | Guard |
|---|---|---|---|
| `/orders` | Order list, scope `my` | customer | `authGuard`, `roleGuard('CLIENT')` |
| `/seller/orders` | Order list, scope `seller` | seller | `authGuard`, `roleGuard('SELLER')` |
| `/orders/:id` | Order details | customer or seller | `authGuard` (the server decides who may see it) |

Navbar links: **My orders** for customers, **Orders** for sellers. The checkout confirmation shows a **View order** link to `/orders/<new id>`.

```
 Navbar ──► /orders ─────────────┐                    Checkout confirmation
            /seller/orders ──────┤                            │
                                 ▼                            ▼
                         Order list page ── View details ──► /orders/:id
                         (filters, paging)                 Order details page
                                                           ├─ customer: Cancel, Order again ──► /cart
                                                           └─ seller:   Update status
```

---

## 3. File structure

```
frontend/src/app
├── core
│   ├── models/order.ts              types + status rules (NEXT_STATUSES, CANCELLABLE)
│   ├── models/order.spec.ts
│   ├── services/order.ts            OrderService: HTTP calls to /api/orders
│   └── services/order.spec.ts
├── features/orders
│   ├── README.md                    this file
│   ├── order-status.ts              status labels, badge colours, short order reference
│   ├── order-list/                  list page (customer and seller)
│   │   ├── order-list.ts / .html / .spec.ts
│   └── order-details/               details page + all actions
│       ├── order-details.ts / .html / .spec.ts
├── app.routes.ts                    the three routes
├── shared/navbar/navbar.html        "My orders" / "Orders" links
└── features/checkout/checkout.html  "View order" link after checkout
```

---

## 4. How the code is organised

```
 Page component (order-list / order-details)
   │  signals: data, busy, error, toast
   ▼
 OrderService (core/services/order.ts) ── HttpClient ──► /api/orders/**
   │                                     (jwt + csrf interceptors add the headers)
   ▼
 Order types (core/models/order.ts)
```

### Models — `core/models/order.ts`

TypeScript types that match the Order Service JSON exactly: `Order`, `OrderItem`, `StatusChange`, `OrderPage`, `OrderFilters`, `ReorderResult`, `OrderStatus`.

Three constants describe the business rules the UI needs to **display** the right options:

| Constant | Value | Used for |
|---|---|---|
| `ORDER_STATUSES` | the 5 statuses in lifecycle order | the status filter dropdown |
| `NEXT_STATUSES` | copy of the backend transition table | the seller's "New status" choices |
| `CANCELLABLE` | `PENDING`, `CONFIRMED` | showing the customer's Cancel button |

They are a convenience, not the protection: the server checks every rule again and answers **409** if the UI and the server ever disagree. `order.spec.ts` checks the table is consistent (every status has an entry, final statuses have none, cancellable statuses can really move to `CANCELLED`).

### API client — `core/services/order.ts`

| Method | Request |
|---|---|
| `list(scope, filters, page, size)` | `GET /api/orders/my` or `/api/orders/seller` with `page`, `size` and the filters that are set |
| `get(id)` | `GET /api/orders/{id}` |
| `cancel(id)` | `PATCH /api/orders/{id}/cancel` |
| `reorder(id)` | `POST /api/orders/{id}/reorder` |
| `updateStatus(id, status, note?)` | `PATCH /api/orders/{id}/status` with `{status, note}` |

Details:

- **One method for both lists.** `scope` is `'my'` or `'seller'`, so the same page serves both roles.
- **Clean query strings.** Empty filters are not sent and the search text is trimmed, so the server never receives `?status=&q=`.
- **Safe URLs.** Order ids go through `encodeURIComponent`.
- **No empty notes.** A blank note is left out of the status request.

### Shared helpers — `features/orders/order-status.ts`

| Function | Example |
|---|---|
| `statusLabel('SHIPPED')` | `Shipped` |
| `statusBadge('SHIPPED')` | `buy-badge buy-badge-warning` |
| `shortId('6718f2a9c3b1e45d2a9f0c11')` | `2A9F0C11` (last 8 characters, used as the visible order reference) |

Badge colours reuse the global `buy-badge-*` styles: pending = neutral, confirmed = primary, shipped = warning, delivered = success, cancelled = danger.

### Why components are tested without TestBed

Components receive their services through the constructor (`new OrderList(orderService, route)`), like the existing cart and checkout pages. Tests pass simple mocks and call methods directly, which keeps them fast and focused on behaviour.

---

## 5. Order list page

`features/orders/order-list` — one component for `/orders` and `/seller/orders`. The route's `data.scope` decides which list is loaded:

```ts
{ path: 'orders',        ..., data: { scope: 'my' } }
{ path: 'seller/orders', ..., data: { scope: 'seller' } }
```

```
┌ My orders ───────────────────────────────────────────────────────────┐
│ [Search: reference or product] [Status ▾] [From] [To] [Apply][Reset] │
├──────────────────────────────────────────────────────────────────────┤
│ Order #2A9F0C11                                        (Shipped)      │
│ Placed Oct 24, 2026, 10:15 AM                                         │
│ Headphones, USB Cable, Lamp +1 more                                   │
│ 5 items · $85.00                                    [View details]    │
├──────────────────────────────────────────────────────────────────────┤
│ …                                                                     │
├──────────────────────────────────────────────────────────────────────┤
│ [Previous]                 Page 1 of 3                       [Next]   │
└──────────────────────────────────────────────────────────────────────┘
```

| Feature | Behaviour |
|---|---|
| Search | matches the exact order id or part of a product name (server side, case-insensitive) |
| Status | `All` or one status |
| From / To | calendar dates (`yyyy-MM-dd`); a start date after the end date is refused **before** any request |
| Apply | searches from page 1 with the current form values |
| Reset | clears the form and reloads |
| Paging | 10 orders per page (`PAGE_SIZE`); Previous / Next keep the applied filters |
| Product names | first three, then `+N more` |
| Empty states | "You have not placed any orders yet", "No orders contain your products yet", or "No orders match these filters" |
| Seller note | "Totals cover only your items", because the server returns the seller's part of each order |

The filters only take effect when **Apply** is pressed (`applied` holds the last submitted filters), so changing a field and then paging doesn't silently mix two searches.

---

## 6. Order details page

`features/orders/order-details` — route `/orders/:id`.

```
← Back to orders
Order #2A9F0C11                                              (Confirmed)
Placed Oct 24, 2026 · Pay on delivery
┌ Actions (customer) / Update status (seller) ─────────────────────────┐
└──────────────────────────────────────────────────────────────────────┘
┌ Products ─────────────────────────────┐ ┌ Delivery address ─────────┐
│ Product      Unit price  Qty  Total   │ │ Sara B.                    │
│ Headphones     $20.00     2   $40.00  │ │ 1 Main St                  │
│ USB Cable       $5.00     1    $5.00  │ │ 20000 Casablanca, Morocco  │
│ Total                     3   $45.00  │ └───────────────────────────┘
└───────────────────────────────────────┘ ┌ Status history ───────────┐
                                          │ (Confirmed) Oct 24 11:02   │
                                          │ (Pending)   Oct 24 10:15   │
                                          │             Order placed   │
                                          └───────────────────────────┘
```

- **Prices at order time.** Lines show the snapshot stored with the order, not today's product price.
- **Timeline.** `statusHistory` is shown newest first (`timeline()` copies the array before reversing, so the order itself is untouched); each entry has its date and the optional note.
- **Seller view.** A seller gets only their own products and totals from the server; the heading reads "Your products in this order".
- **Back link** goes to `/orders` or `/seller/orders` depending on the role.
- **Unknown or foreign order.** A 404 or 403 shows "This order does not exist or is not yours." with a Retry button.

---

## 7. Customer actions: cancel and order again

### Cancel

```
[Cancel order] ──► "Cancel this order?" [Yes, cancel it] [Keep order]
                                              │
                         PATCH /api/orders/{id}/cancel
                                              │
              200 ──► page shows CANCELLED + toast "Order cancelled…"
              409 ──► error with the server's reason (e.g. already shipped)
```

- The button appears only while the order is `PENDING` or `CONFIRMED` (`canCancel`).
- A confirmation step avoids cancelling by accident; no browser pop-up is used.
- The server releases the reserved stock; the page shows the returned order, so the timeline updates immediately.

### Order again

```
[Order again]
     │  POST /api/orders/{id}/reorder        → [{productId, quantity}, …]
     │  for each item, one after the other:
     │    POST /api/cart/items {productId, quantity}
     │      ok   → counted as added
     │      fail → counted as unavailable (first reason kept)
     ▼
 all added   → toast "All products were added to your cart." + [Go to cart]
 some added  → toast "2 of 3 products were added to your cart. The others are unavailable." + [Go to cart]
 none added  → error "None of these products could be added to your cart (<reason>)."
```

- Items are added **in sequence** (`concatMap`), so the cart sees one change at a time.
- A failure on one product (deleted, out of stock) doesn't stop the others (`catchError` per item).
- Prices are **not** copied: the cart and the new checkout use today's prices and stock.
- Nothing is ordered automatically; the customer reviews the cart and checks out as usual.

---

## 8. Seller action: update the status

```
Update status
[New status ▾ Shipped / Cancelled]  [Note for the customer (optional)]  [Update status]
The status applies to the whole order, including other sellers' products.
```

- The dropdown lists only `NEXT_STATUSES[current status]`, e.g. `CONFIRMED` → `Shipped`, `Cancelled`.
- The note is optional, trimmed, and at most 255 characters (checked in the form and before sending).
- On success the form resets, the page shows the updated order and a toast says "Order marked as shipped."
- A finished order (`DELIVERED`, `CANCELLED`) shows "its status can no longer change" instead of the form.
- If someone else changed the order first, the server answers 409 and the page shows its message.

---

## 9. Feedback: loading, errors, toasts

Each page keeps its state in Angular **signals**:

| Signal | Shown as |
|---|---|
| `busy` | "Loading orders…" / "Working…" and disabled buttons, so a request can't be sent twice |
| `error` | a red alert with the server's `message`, or a friendly fallback if there is none |
| `toast` | a notification in the bottom-right corner (global `buy-toast` style) that disappears after 4 seconds |

The server always answers errors with the same JSON shape, so `error.message` can be shown directly. Messages for network failures are written for people ("Unable to load orders. Please retry.").

---

## 10. Security in the UI

- **Login and role guards** keep users away from pages that aren't theirs, but they are only a convenience: every request goes through the gateway, which checks the JWT, and the Order Service checks ownership and roles again.
- **No identity is sent by the UI.** The JWT interceptor adds `Authorization`; the gateway derives `X-User-Id` / `X-User-Roles` from it and removes any copy a client tries to send.
- **CSRF.** Write requests (cancel, reorder, status, cart) carry the `X-XSRF-TOKEN` header from the existing CSRF interceptor.
- **No prices from the browser.** Reorder sends only product ids and quantities; prices always come from the server.
- **Angular escaping.** Product names and notes are rendered with interpolation, never as HTML.

---

## 11. Responsive layout and accessibility

- Bootstrap grid: filters stack on phones and sit on one row on desktop; on the details page the address and timeline move under the products on small screens; the products table scrolls horizontally if needed.
- Every form field has a `<label>`; the filter form has `role="search"`; paging is a `<nav>` with a label.
- Loading and toast messages use `<output>` / `aria-live="polite"`, so screen readers announce them.
- "View details" links have an `aria-label` with the order reference; the cancel confirmation is a `<fieldset>` whose `<legend>` asks the question.
- Status is shown as text inside the badge, not by colour alone.

---

## 12. Tests

```bash
cd frontend
npm run test:coverage
```

| Spec | Checks |
|---|---|
| `core/models/order.spec.ts` | status table is complete and consistent |
| `core/services/order.spec.ts` | URL, method, query parameters and body of every call; filter cleaning; id encoding; blank notes |
| `order-list/order-list.spec.ts` | default and seller scope, filters + reset + paging, date check, error messages, product-name summary, status helpers |
| `order-details/order-details.spec.ts` | loading by route id, back link per role, timeline order, error messages (404/403/502/network), cancel (success, refused, confirmation reset, toast timer), reorder (all / some / none added, failed request), busy guard, seller next statuses, status update (success, no status, note too long, refused) |

Results at the time of writing: **33 test files, 199 tests, all passing**. The order files are at 100 % line coverage; the whole frontend is above the required 80 % for statements, branches, functions and lines.

---

## 13. Pull requests

The UI was delivered as a stack of small pull requests, each one building on the previous:

| # | Branch | Content |
|---|---|---|
| 1 | `orders-ui-api` (#8, merged) | models and `OrderService` |
| 2 | `orders-ui-list` (#10) | list pages, filters, paging, navbar links |
| 3 | `orders-ui-details` | details page, timeline, links from the lists |
| 4 | `orders-ui-actions` | cancel, order again, toasts, link from checkout |
| 5 | `orders-ui-seller-status` | seller status update |
| 6 | `orders-docs` | this README and the Order Service README |

---

## 14. Limitations

| Limitation | Why | Possible follow-up |
|---|---|---|
| One status for the whole order | the backend stores one status per order | per-seller status once the backend supports it |
| Dates are UTC days | the server filters by UTC | send the browser's time zone |
| Order again adds items one request at a time | the cart API adds one product per call | a bulk "add items" endpoint |
| No live updates | the page shows the order as loaded | refresh after a while, or server-sent events |
| Amounts shown in USD | the existing cart and checkout use the `USD` currency pipe | one shared currency setting |
