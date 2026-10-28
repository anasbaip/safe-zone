import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ProductService } from './product';

describe('ProductService search', () => {
  let service: ProductService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ProductService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('calls the paginated search endpoint with all supplied filters', () => {
    const response = { content: [], totalElements: 0, totalPages: 0, page: 2, size: 24 };
    let received: unknown;
    service.searchProducts({ q: 'oak & wood', category: 'furniture', minPrice: 0,
      maxPrice: 100, inStock: true, sort: 'price-asc', page: 2, size: 24 }).subscribe(value => received = value);
    const request = http.expectOne(req => req.url === '/api/products/search');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('q')).toBe('oak & wood');
    expect(request.request.params.get('category')).toBe('furniture');
    expect(request.request.params.get('minPrice')).toBe('0');
    expect(request.request.params.get('maxPrice')).toBe('100');
    expect(request.request.params.get('inStock')).toBe('true');
    expect(request.request.params.get('sort')).toBe('price-asc');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('24');
    request.flush(response);
    expect(received).toEqual(response);
  });

  it('omits absent filters while preserving false and page zero', () => {
    service.searchProducts({ q: '', category: undefined, minPrice: null, inStock: false, page: 0 }).subscribe();
    const request = http.expectOne(req => req.url === '/api/products/search');
    expect(request.request.params.keys().sort()).toEqual(['inStock', 'page']);
    expect(request.request.params.get('inStock')).toBe('false');
    expect(request.request.params.get('page')).toBe('0');
    request.flush({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 12 });
  });

  it('supports an unfiltered search', () => {
    service.searchProducts().subscribe();
    const request = http.expectOne('/api/products/search');
    expect(request.request.params.keys()).toEqual([]);
    request.flush({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 12 });
  });
});
