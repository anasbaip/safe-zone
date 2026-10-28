import { of, throwError } from 'rxjs';
import { NgForm } from '@angular/forms';
import { CartService } from '../../core/services/cart';
import { Checkout } from './checkout';
import { CartData } from '../../core/models/cart';

describe('Checkout', () => {
  const address = { fullName: 'Jane', phone: '123', street: 'Street', city: 'City', postalCode: '1', country: 'Morocco' };
  const data: CartData = { items: [{ productId: 'p', name: 'Product', image: null, quantity: 1, availableQuantity: 2,
    unitPrice: 10, lineTotal: 10, available: true }], totalAmount: 10, reviewToken: 'token', checkoutPending: false, pending: null };
  const form = { invalid: false } as NgForm;
  let api: { get: ReturnType<typeof vi.fn>; review: ReturnType<typeof vi.fn>; checkout: ReturnType<typeof vi.fn> };
  let page: Checkout;
  beforeEach(() => {
    api = { get: vi.fn(() => of(data)), review: vi.fn(() => of(data)), checkout: vi.fn(() => of({ id: 'order', totalAmount: 10 })) };
    page = new Checkout(api as unknown as CartService); page.address = { ...address }; page.ngOnInit();
  });
  it('reviews before confirming and shows success only after acknowledgement', () => {
    page.review(form); expect(page.step()).toBe('review'); expect(page.order()).toBeNull();
    page.confirm(); expect(page.step()).toBe('done'); expect(page.order()?.id).toBe('order');
  });
  it('retains the same key and request after an uncertain failure', () => {
    api.checkout.mockReturnValue(throwError(() => ({ status: 502, error: { message: 'not confirmed' } })));
    page.review(form); page.confirm(); const first = api.checkout.mock.calls[0];
    expect(page.step()).toBe('review'); expect(page.pending()).toBe(true); expect(page.order()).toBeNull();
    page.confirm(); expect(api.checkout.mock.calls[1]).toEqual(first);
  });
  it('recovers persisted pending checkout on reload', () => {
    api.get.mockReturnValue(of({ ...data, checkoutPending: true, pending: { key: 'original-key', shippingAddress: address, reviewToken: 'original-review' } }));
    page.load(); page.confirm(); expect(api.checkout).toHaveBeenCalledWith(address, 'original-review', 'original-key');
  });
  it('requires a fresh review after a price conflict without a pending checkout', () => {
    api.checkout.mockReturnValue(throwError(() => ({ status: 409, error: { message: 'prices changed' } })));
    page.review(form); page.confirm(); expect(page.step()).toBe('address'); expect(page.pending()).toBe(false);
  });
});
