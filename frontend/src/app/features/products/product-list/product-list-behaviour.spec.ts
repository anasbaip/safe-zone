import { Observable, Subject, of, throwError } from 'rxjs';
import { ProductList } from './product-list';
import { ProductService } from '../../../core/services/product';
import { CartService } from '../../../core/services/cart';
import { AuthService } from '../../../core/services/auth';
import { Router } from '@angular/router';
import { Product, ProductSearchFilters, ProductSearchResponse } from '../../../core/models/product';

const products: Product[] = [
  { id: '1', name: 'Apple', description: 'red fruit', price: 10, quantity: 5, images: ['a.png'] },
  { id: '2', name: 'Banana', description: 'yellow', price: 5, quantity: 0, images: [] },
  { id: '3', name: 'Cherry', description: '', price: 20, quantity: 2, images: ['c.png'] },
];

describe('ProductList behaviour', () => {
  let component: ProductList;
  let serviceMock: { searchProducts: ReturnType<typeof vi.fn> };
  let cartMock: { add: ReturnType<typeof vi.fn> };
  let authMock: { isLoggedIn: ReturnType<typeof vi.fn>; hasRole: ReturnType<typeof vi.fn> };
  let routerMock: { navigate: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    vi.useFakeTimers();
    serviceMock = { searchProducts: vi.fn((filters: ProductSearchFilters) => of({
      content: products, totalElements: 30, totalPages: 3, page: filters.page ?? 0, size: 12,
    })) };
    cartMock = { add: vi.fn(() => of({})) };
    authMock = { isLoggedIn: vi.fn(() => true), hasRole: vi.fn(() => true) };
    routerMock = { navigate: vi.fn() };
    component = new ProductList(serviceMock as unknown as ProductService,
      cartMock as unknown as CartService, authMock as unknown as AuthService, routerMock as unknown as Router);
    component.ngOnInit();
    vi.advanceTimersByTime(0);
    serviceMock.searchProducts.mockClear();
  });

  afterEach(() => {
    component.ngOnDestroy();
    vi.useRealTimers();
  });

  it('loads the server page and totals', () => {
    expect(component.products()).toEqual(products);
    expect(component.totalElements()).toBe(30);
    expect(component.totalPages()).toBe(3);
    expect(component.loading()).toBe(false);
  });

  it('debounces typing and sends the final keyword to the server', () => {
    component.filters.controls.q.setValue('ph');
    vi.advanceTimersByTime(200);
    component.filters.controls.q.setValue('phone');
    vi.advanceTimersByTime(299);
    expect(serviceMock.searchProducts).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(serviceMock.searchProducts).toHaveBeenCalledOnce();
    expect(serviceMock.searchProducts).toHaveBeenCalledWith(expect.objectContaining({ q: 'phone', page: 0 }));
  });

  it('sends category, prices, stock, sorting and page size together', () => {
    component.filters.patchValue({ category: ' Furniture ', minPrice: 10, maxPrice: 50,
      inStock: true, sort: 'price-desc', size: 24 });
    vi.advanceTimersByTime(300);
    expect(serviceMock.searchProducts).toHaveBeenCalledWith(expect.objectContaining({
      category: 'furniture', minPrice: 10, maxPrice: 50, inStock: true, sort: 'price-desc', size: 24,
    }));
    // The UI displays the returned page as-is; it does not filter or sort it locally.
    expect(component.products()).toEqual(products);
  });

  it('paginates and resets to page zero when filters change', () => {
    component.changePage(1);
    vi.advanceTimersByTime(0);
    expect(serviceMock.searchProducts).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }));
    component.filters.controls.inStock.setValue(true);
    vi.advanceTimersByTime(300);
    expect(serviceMock.searchProducts).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, inStock: true }));
  });

  it('ignores invalid and redundant page navigation', () => {
    component.changePage(-1);
    component.changePage(3);
    component.changePage(0);
    expect(serviceMock.searchProducts).not.toHaveBeenCalled();
  });

  it('cancels the previous HTTP request as soon as filters change', () => {
    const cancelled = vi.fn();
    serviceMock.searchProducts.mockReturnValue(new Observable(() => cancelled));
    component.loadProducts();
    vi.advanceTimersByTime(0);
    component.filters.controls.q.setValue('new');
    expect(cancelled).toHaveBeenCalledOnce();
    expect(component.products()).toEqual([]);
  });

  it('does not issue requests for invalid prices and recovers after correction', () => {
    component.filters.patchValue({ minPrice: 100, maxPrice: 50 });
    vi.advanceTimersByTime(300);
    expect(component.filters.hasError('priceRange')).toBe(true);
    expect(serviceMock.searchProducts).not.toHaveBeenCalled();
    component.filters.controls.maxPrice.setValue(200);
    vi.advanceTimersByTime(300);
    expect(serviceMock.searchProducts).toHaveBeenCalledOnce();
    component.filters.controls.minPrice.setValue(-1);
    expect(component.filters.invalid).toBe(true);
    component.filters.controls.minPrice.setValue(1.001);
    expect(component.filters.invalid).toBe(true);
  });

  it('reports an API error and retries without terminating the search stream', () => {
    serviceMock.searchProducts.mockReturnValue(throwError(() => new Error('down')));
    component.loadProducts();
    vi.advanceTimersByTime(0);
    expect(component.loadError()).toContain('Unable to retrieve');
    expect(component.loading()).toBe(false);
    serviceMock.searchProducts.mockReturnValue(of({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 12 }));
    component.loadProducts();
    vi.advanceTimersByTime(0);
    expect(component.loadError()).toBe('');
    expect(component.products()).toEqual([]);
  });

  it('clearSearch resets every filter and pagination', () => {
    component.filters.patchValue({ q: 'x', category: 'furniture', minPrice: 1, maxPrice: 5,
      inStock: true, sort: 'price-asc', size: 24 });
    expect(component.hasFilters()).toBe(true);
    component.clearSearch();
    vi.advanceTimersByTime(0);
    expect(component.hasFilters()).toBe(false);
    expect(serviceMock.searchProducts).toHaveBeenLastCalledWith({ q: '', category: '', minPrice: null,
      maxPrice: null, inStock: false, sort: 'newest', page: 0, size: 12 });
  });

  it('cancels pending search and HTTP work on destruction', () => {
    const result = new Subject<ProductSearchResponse>();
    serviceMock.searchProducts.mockReturnValue(result);
    component.loadProducts();
    vi.advanceTimersByTime(0);
    component.ngOnDestroy();
    result.next({ content: products, totalElements: 3, totalPages: 1, page: 0, size: 12 });
    expect(component.products()).toEqual([]);
  });

  it('keeps authentication and stock checks when adding to cart', () => {
    authMock.isLoggedIn.mockReturnValue(false);
    component.addToOrder(products[0]);
    expect(routerMock.navigate).toHaveBeenCalledWith(['/login']);
    authMock.isLoggedIn.mockReturnValue(true);
    authMock.hasRole.mockReturnValue(false);
    component.addToOrder(products[0]);
    expect(component.toastMessage()).toContain('Only clients');
    authMock.hasRole.mockReturnValue(true);
    component.addToOrder(products[1]);
    expect(cartMock.add).not.toHaveBeenCalled();
    component.addToOrder(products[0]);
    expect(cartMock.add).toHaveBeenCalledWith('1');
  });

  it('manages quick view modal', () => {
    component.openQuickView(products[0]);
    expect(component.selectedProduct()?.id).toBe('1');
    expect(component.activeModalImage()).toBe('a.png');
    component.setActiveModalImage('other.png');
    expect(component.activeModalImage()).toBe('other.png');
    component.closeQuickView();
    expect(component.selectedProduct()).toBeNull();
  });

  it('opens quick view with no images', () => {
    component.openQuickView(products[1]);
    expect(component.activeModalImage()).toBe('');
  });

  it('escape closes the modal only when open', () => {
    component.onEscapePress();
    expect(component.selectedProduct()).toBeNull();
    component.openQuickView(products[0]);
    component.onEscapePress();
    expect(component.selectedProduct()).toBeNull();
  });

  it('shows a toast when adding to order', () => {
    component.addToOrder(products[0]);
    expect(component.toastMessage()).toContain('Apple');
    vi.advanceTimersByTime(3600);
    expect(component.toastMessage()).toBe('');
  });

  it('getPrimaryImage handles presence and absence', () => {
    expect(component.getPrimaryImage(products[0])).toBe('a.png');
    expect(component.getPrimaryImage(products[1])).toBe('');
  });

  it('onImageError hides the image and reveals the fallback', () => {
    const img = document.createElement('img');
    const fallback = document.createElement('div');
    const container = document.createElement('div');
    container.append(img, fallback);
    component.onImageError({ target: img } as unknown as Event);
    expect(img.style.display).toBe('none');
    expect(fallback.style.display).toBe('flex');
  });
});