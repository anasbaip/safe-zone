import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { OrderService } from './order';

describe('OrderService API', () => {
  let service: OrderService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(OrderService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('lists customer orders with paging and only the filters that are set', () => {
    service.list('my', { status: 'SHIPPED', from: '2026-01-01', to: '', q: '  head ' }, 2, 5).subscribe();
    const req = http.expectOne(r => r.url === '/api/orders/my');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys().sort()).toEqual(['from', 'page', 'q', 'size', 'status']);
    expect(req.request.params.get('q')).toBe('head');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('5');
    req.flush({});
  });

  it('lists seller orders with default paging', () => {
    service.list('seller').subscribe();
    const req = http.expectOne(r => r.url === '/api/orders/seller');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('10');
    req.flush({});
  });

  it('encodes the order id when reading, cancelling and reordering', () => {
    service.get('a/b').subscribe();
    http.expectOne('/api/orders/a%2Fb').flush({});

    service.cancel('o1').subscribe();
    const cancel = http.expectOne('/api/orders/o1/cancel');
    expect(cancel.request.method).toBe('PATCH');
    cancel.flush({});

    service.reorder('o1').subscribe();
    const reorder = http.expectOne('/api/orders/o1/reorder');
    expect(reorder.request.method).toBe('POST');
    reorder.flush({});
  });

  it('sends a trimmed note with a status change, and omits a blank one', () => {
    service.updateStatus('o1', 'SHIPPED', '  tracking 42 ').subscribe();
    const withNote = http.expectOne('/api/orders/o1/status');
    expect(withNote.request.method).toBe('PATCH');
    expect(withNote.request.body).toEqual({ status: 'SHIPPED', note: 'tracking 42' });
    withNote.flush({});

    service.updateStatus('o1', 'DELIVERED', '   ').subscribe();
    expect(http.expectOne('/api/orders/o1/status').request.body).toEqual({ status: 'DELIVERED' });
  });
});
