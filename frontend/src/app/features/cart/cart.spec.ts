import { of, Subject, throwError } from 'rxjs';
import { CartData } from '../../core/models/cart';
import { CartService } from '../../core/services/cart';
import { Cart } from './cart';

describe('Cart', () => {
  const data: CartData = {
    items: [{ productId: 'p', name: 'Product', image: null, quantity: 1,
      availableQuantity: 2, unitPrice: 10, lineTotal: 10, available: true }],
    totalAmount: 10, reviewToken: 'token', checkoutPending: false, pending: null,
  };
  let api: { get: ReturnType<typeof vi.fn>; update: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>; clear: ReturnType<typeof vi.fn> };
  let page: Cart;

  beforeEach(() => {
    api = { get: vi.fn(() => of(data)), update: vi.fn(() => of(data)),
      remove: vi.fn(() => of(data)), clear: vi.fn(() => of({ ...data, items: [], totalAmount: 0 })) };
    page = new Cart(api as unknown as CartService);
  });

  it('loads the cart on initialization and enables checkout for available items', () => {
    expect(page.canCheckout()).toBe(false);
    page.ngOnInit();
    expect(api.get).toHaveBeenCalledOnce();
    expect(page.data()).toEqual(data);
    expect(page.canCheckout()).toBe(true);
    expect(page.busy()).toBe(false);
    page.data.set({ ...data, items: [{ ...data.items[0], available: false }] });
    expect(page.canCheckout()).toBe(false);
    page.data.set({ ...data, items: [] });
    expect(page.canCheckout()).toBe(false);
  });

  it.each(['abc', '1.5', '0', '101'])('rejects invalid quantity %s without changing the cart', raw => {
    page.update('p', raw);
    expect(api.update).not.toHaveBeenCalled();
    expect(page.error()).toBe('Quantity must be a whole number from 1 to 100.');
  });

  it.each(['1', '100'])('accepts quantity boundary %s', raw => {
    page.update('p', raw);
    expect(api.update).toHaveBeenCalledWith('p', Number(raw));
    expect(page.data()).toEqual(data);
    expect(page.error()).toBe('');
  });

  it('removes items and clears the cart', () => {
    page.remove('p');
    expect(api.remove).toHaveBeenCalledWith('p');
    page.clear();
    expect(api.clear).toHaveBeenCalledOnce();
    expect(page.data()?.items).toEqual([]);
    expect(page.canCheckout()).toBe(false);
  });

  it('ignores another request while loading and releases the busy state on success', () => {
    const pending = new Subject<CartData>();
    const ignored = new Subject<CartData>();
    page.run(pending);
    expect(page.busy()).toBe(true);
    page.run(ignored);
    ignored.next({ ...data, items: [] });
    expect(page.data()).toBeNull();
    pending.next(data);
    pending.complete();
    expect(page.data()).toEqual(data);
    expect(page.busy()).toBe(false);
  });

  it.each([
    [{ error: { message: 'Stock changed' } }, 'Stock changed'],
    [{ error: {} }, 'Unable to update your cart. Please retry.'],
    [{}, 'Unable to update your cart. Please retry.'],
  ])('shows request errors and allows a successful reload', (error, message) => {
    page.run(throwError(() => error));
    expect(page.error()).toBe(message);
    expect(page.busy()).toBe(false);
    page.load();
    expect(page.error()).toBe('');
    expect(page.data()).toEqual(data);
  });
});
