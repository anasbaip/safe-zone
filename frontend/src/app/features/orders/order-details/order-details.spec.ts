import { of, throwError } from 'rxjs';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { Order } from '../../../core/models/order';
import { AuthService } from '../../../core/services/auth';
import { CartService } from '../../../core/services/cart';
import { OrderService } from '../../../core/services/order';
import { NOTE_MAX, OrderDetails } from './order-details';

const sampleOrder = (overrides: Partial<Order> = {}): Order => ({
  id: 'order-000123',
  customerId: 'customer-1',
  status: 'PENDING',
  items: [
    { productId: 'p1', sellerId: 's1', productName: 'Lamp', category: 'lighting', imageUrl: null, unitPrice: 10, quantity: 2, lineTotal: 20 },
  ],
  shippingAddress: { fullName: 'Sara', phone: '06', street: '1 Main St', city: 'Rabat', postalCode: '10000', country: 'Morocco' },
  paymentMethod: 'CASH_ON_DELIVERY',
  totalItems: 2,
  totalAmount: 20,
  statusHistory: [
    { status: 'PENDING', changedAt: '2026-10-01T10:00:00Z', changedBy: 'customer-1', note: 'Order placed' },
    { status: 'CONFIRMED', changedAt: '2026-10-02T10:00:00Z', changedBy: 's1', note: null },
  ],
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-02T10:00:00Z',
  ...overrides,
});

describe('OrderDetails', () => {
  let api: {
    get: ReturnType<typeof vi.fn>;
    cancel: ReturnType<typeof vi.fn>;
    reorder: ReturnType<typeof vi.fn>;
    updateStatus: ReturnType<typeof vi.fn>;
  };
  let cart: { add: ReturnType<typeof vi.fn> };

  const create = (role: 'CLIENT' | 'SELLER' = 'CLIENT', id: string | null = 'order-000123') =>
    new OrderDetails(
      api as unknown as OrderService,
      cart as unknown as CartService,
      { hasRole: (r: string) => r === role } as unknown as AuthService,
      { snapshot: { paramMap: convertToParamMap(id ? { id } : {}) } } as unknown as ActivatedRoute,
    );

  beforeEach(() => {
    api = {
      get: vi.fn(() => of(sampleOrder())),
      cancel: vi.fn(() => of(sampleOrder({ status: 'CANCELLED' }))),
      updateStatus: vi.fn(() => of(sampleOrder({ status: 'SHIPPED' }))),
      reorder: vi.fn(() =>
        of({ sourceOrderId: 'order-000123', items: [{ productId: 'p1', quantity: 2 }, { productId: 'p2', quantity: 1 }] }),
      ),
    };
    cart = { add: vi.fn(() => of({})) };
  });

  afterEach(() => vi.useRealTimers());

  it('loads the order from the route id', () => {
    const page = create();
    page.ngOnInit();
    expect(api.get).toHaveBeenCalledWith('order-000123');
    expect(page.order()?.id).toBe('order-000123');
    expect(page.busy()).toBe(false);
    expect(page.seller).toBe(false);
    expect(page.backLink).toBe('/orders');
  });

  it('links sellers back to their order list', () => {
    const page = create('SELLER');
    expect(page.seller).toBe(true);
    expect(page.backLink).toBe('/seller/orders');
  });

  it('shows the newest status change first without changing the order', () => {
    const order = sampleOrder();
    const page = create();
    expect(page.timeline(order).map(c => c.status)).toEqual(['CONFIRMED', 'PENDING']);
    expect(order.statusHistory[0].status).toBe('PENDING');
  });

  it.each([
    [{ status: 404 }, 'This order does not exist or is not yours.'],
    [{ status: 403 }, 'This order does not exist or is not yours.'],
    [{ status: 502, error: { message: 'Product Service is unavailable' } }, 'Product Service is unavailable'],
    [{ status: 0 }, 'Unable to load this order. Please retry.'],
  ])('explains load failures', (failure, message) => {
    api.get.mockReturnValue(throwError(() => failure));
    const page = create('CLIENT', null);
    page.ngOnInit();
    expect(api.get).toHaveBeenCalledWith('');
    expect(page.error()).toBe(message);
    expect(page.busy()).toBe(false);
  });

  it('offers cancellation to the customer only before shipping', () => {
    const customer = create();
    expect(customer.canCancel(sampleOrder({ status: 'PENDING' }))).toBe(true);
    expect(customer.canCancel(sampleOrder({ status: 'CONFIRMED' }))).toBe(true);
    expect(customer.canCancel(sampleOrder({ status: 'SHIPPED' }))).toBe(false);
    expect(create('SELLER').canCancel(sampleOrder({ status: 'PENDING' }))).toBe(false);
  });

  it('cancels the order and confirms it with a toast that disappears', () => {
    vi.useFakeTimers();
    const page = create();
    page.confirmingCancel.set(true);
    page.cancel();
    expect(api.cancel).toHaveBeenCalledWith('order-000123');
    expect(page.order()?.status).toBe('CANCELLED');
    expect(page.confirmingCancel()).toBe(false);
    expect(page.toast()).toContain('Order cancelled');
    vi.advanceTimersByTime(4000);
    expect(page.toast()).toBe('');
  });

  it('shows why a cancellation was refused', () => {
    api.cancel.mockReturnValue(throwError(() => ({ status: 409, error: { message: 'Cannot change order status from SHIPPED to CANCELLED' } })));
    const page = create();
    page.cancel();
    expect(page.error()).toBe('Cannot change order status from SHIPPED to CANCELLED');
    expect(page.busy()).toBe(false);
  });

  it('adds every product back to the cart in order', () => {
    const page = create();
    page.reorder();
    expect(cart.add.mock.calls).toEqual([['p1', 2], ['p2', 1]]);
    expect(page.reordered()).toBe(true);
    expect(page.toast()).toBe('All products were added to your cart.');
    expect(page.busy()).toBe(false);
    page.ngOnDestroy();
  });

  it('reports products that could not be added', () => {
    cart.add.mockReturnValueOnce(throwError(() => ({ status: 409 }))).mockReturnValueOnce(of({}));
    const page = create();
    page.reorder();
    expect(page.toast()).toBe('1 of 2 products were added to your cart. The others are unavailable.');
    expect(page.reordered()).toBe(true);
  });

  it('explains when nothing could be added', () => {
    cart.add.mockReturnValue(throwError(() => ({ status: 409, error: { message: 'Product unavailable or insufficient stock' } })));
    const page = create();
    page.reorder();
    expect(page.reordered()).toBe(false);
    expect(page.toast()).toBe('');
    expect(page.error()).toBe('None of these products could be added to your cart (Product unavailable or insufficient stock).');
  });

  it('falls back to a generic reason and handles a failed reorder request', () => {
    cart.add.mockReturnValue(throwError(() => ({ status: 0 })));
    const page = create();
    page.reorder();
    expect(page.error()).toBe('None of these products could be added to your cart (unavailable).');

    api.reorder.mockReturnValue(throwError(() => ({ status: 500 })));
    page.reorder();
    expect(page.error()).toBe('The order could not be repeated. Please retry.');
  });

  it('ignores actions while a request is running', () => {
    const page = create();
    page.busy.set(true);
    page.cancel();
    page.reorder();
    expect(api.cancel).not.toHaveBeenCalled();
    expect(api.reorder).not.toHaveBeenCalled();
  });

  it('offers sellers only the valid next statuses', () => {
    const seller = create('SELLER');
    expect(seller.nextStatuses(sampleOrder({ status: 'CONFIRMED' }))).toEqual(['SHIPPED', 'CANCELLED']);
    expect(seller.nextStatuses(sampleOrder({ status: 'DELIVERED' }))).toEqual([]);
    expect(create().nextStatuses(sampleOrder({ status: 'PENDING' }))).toEqual([]);
  });

  it('updates the status with the note and resets the form', () => {
    const page = create('SELLER');
    page.nextStatus = 'SHIPPED';
    page.note = 'tracking 42';
    page.updateStatus();
    expect(api.updateStatus).toHaveBeenCalledWith('order-000123', 'SHIPPED', 'tracking 42');
    expect(page.order()?.status).toBe('SHIPPED');
    expect(page.nextStatus).toBe('');
    expect(page.note).toBe('');
    expect(page.toast()).toBe('Order marked as shipped.');
    page.ngOnDestroy();
  });

  it('does nothing without a chosen status, and rejects a note that is too long', () => {
    const page = create('SELLER');
    page.updateStatus();
    expect(api.updateStatus).not.toHaveBeenCalled();

    page.nextStatus = 'SHIPPED';
    page.note = 'x'.repeat(NOTE_MAX + 1);
    page.updateStatus();
    expect(api.updateStatus).not.toHaveBeenCalled();
    expect(page.error()).toBe(`The note must be at most ${NOTE_MAX} characters.`);
  });

  it('shows why a status change was refused', () => {
    api.updateStatus.mockReturnValue(throwError(() => ({ status: 409, error: { message: 'Cannot change order status from SHIPPED to CONFIRMED' } })));
    const page = create('SELLER');
    page.nextStatus = 'CONFIRMED';
    page.updateStatus();
    expect(page.error()).toBe('Cannot change order status from SHIPPED to CONFIRMED');
    expect(page.busy()).toBe(false);

    api.updateStatus.mockReturnValue(throwError(() => ({ status: 0 })));
    page.updateStatus();
    expect(page.error()).toBe('The status could not be updated. Please retry.');
  });
});
