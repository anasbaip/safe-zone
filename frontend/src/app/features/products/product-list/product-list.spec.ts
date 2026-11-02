import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ProductList } from './product-list';
import { ProductService } from '../../../core/services/product';
import { CartService } from '../../../core/services/cart';
import { AuthService } from '../../../core/services/auth';

describe('ProductList template', () => {
  let component: ProductList;
  let fixture: ComponentFixture<ProductList>;
  const service = { searchProducts: vi.fn() };

  beforeEach(async () => {
    service.searchProducts.mockReturnValue(of({ content: [
      { id: 'p', name: 'Chair', description: 'Wooden chair', category: 'furniture', price: 20, quantity: 2 }
    ], totalElements: 25, totalPages: 3, page: 0, size: 12 }));
    await TestBed.configureTestingModule({ imports: [ProductList], providers: [provideRouter([]),
      { provide: ProductService, useValue: service },
      { provide: CartService, useValue: { add: vi.fn(() => of({})) } },
      { provide: AuthService, useValue: { isLoggedIn: () => false, hasRole: () => false } },
    ] }).compileComponents();
    vi.useFakeTimers();
    fixture = TestBed.createComponent(ProductList);
    component = fixture.componentInstance;
    fixture.detectChanges();
    vi.advanceTimersByTime(0);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    vi.useRealTimers();
  });

  it('renders server totals, category and pagination', () => {
    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('25');
    expect(element.textContent).toContain('furniture');
    expect(element.textContent).toContain('Page 1 of 3');
    const buttons = element.querySelectorAll('nav[aria-label="Product pages"] button');
    expect((buttons[0] as HTMLButtonElement).disabled).toBe(true);
    expect((buttons[1] as HTMLButtonElement).disabled).toBe(false);
  });

  it('shows inline invalid-price feedback and suppresses API requests', () => {
    service.searchProducts.mockClear();
    component.filters.patchValue({ minPrice: 50, maxPrice: 10 });
    vi.advanceTimersByTime(300);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#priceFilterError')?.textContent).toContain('Minimum must not exceed maximum');
    expect(service.searchProducts).not.toHaveBeenCalled();
  });

  it('shows the empty state when the server finds no matches', () => {
    service.searchProducts.mockReturnValue(of({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 12 }));
    component.loadProducts();
    vi.advanceTimersByTime(0);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No matching pieces found');
    expect(fixture.nativeElement.querySelector('nav[aria-label="Product pages"]')).toBeNull();
  });
});
