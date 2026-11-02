import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CartService } from './cart';

describe('CartService API', () => {
  let service: CartService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(CartService); http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('sends only product ID and quantity when adding', () => {
    service.add('p1', 2).subscribe();
    const req = http.expectOne('/api/cart/items');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ productId: 'p1', quantity: 2 }); req.flush({});
  });
  it('encodes product IDs and uses PUT for exact quantities', () => {
    service.update('p/1', 3).subscribe();
    const req = http.expectOne('/api/cart/items/p%2F1');
    expect(req.request.method).toBe('PUT'); expect(req.request.body).toEqual({ quantity: 3 }); req.flush({});
  });
  it('confirms with an idempotency key and no owner or prices', () => {
    const address = { fullName: 'Jane', phone: '123', street: 'Street', city: 'City', postalCode: '1', country: 'Morocco' };
    service.checkout(address, 'review', 'key').subscribe();
    const req = http.expectOne('/api/cart/checkout');
    expect(req.request.headers.get('Idempotency-Key')).toBe('key');
    expect(req.request.body).toEqual({ shippingAddress: address, reviewToken: 'review' }); req.flush({});
  });
});
