import { Component, OnInit, OnDestroy, signal, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { EMPTY, Subject, Subscription, catchError, switchMap, timer } from 'rxjs';
import { ProductService } from '../../../core/services/product';
import { CartService } from '../../../core/services/cart';
import { AuthService } from '../../../core/services/auth';
import { Router } from '@angular/router';
import { Product, ProductSearchFilters, ProductSort } from '../../../core/models/product';

@Component({
  selector: 'app-product-list',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './product-list.html',
  styleUrl: './product-list.css',
})
export class ProductList implements OnInit, OnDestroy {
  readonly products = signal<Product[]>([]);
  readonly loading = signal<boolean>(true);
  readonly loadError = signal<string>('');

  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly page = signal(0);
  readonly viewMode = signal<'grid' | 'list'>('grid');
  readonly filters = new FormGroup({
    q: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    category: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(80)] }),
    minPrice: new FormControl<number | null>(null, [Validators.min(0), Validators.pattern(/^\d{1,12}(?:\.\d{1,2})?$/)]),
    maxPrice: new FormControl<number | null>(null, [Validators.min(0), Validators.pattern(/^\d{1,12}(?:\.\d{1,2})?$/)]),
    inStock: new FormControl(false, { nonNullable: true }),
    sort: new FormControl<ProductSort>('newest', { nonNullable: true }),
    size: new FormControl(12, { nonNullable: true }),
  }, { validators: control => {
    const { minPrice, maxPrice } = control.value;
    return minPrice != null && maxPrice != null && minPrice > maxPrice ? { priceRange: true } : null;
  } });

  private readonly requests = new Subject<{ filters: ProductSearchFilters; delay: number } | null>();
  private readonly subscriptions = new Subscription();

  // Interactive Quick View Modal (R-26, C-2)
  readonly selectedProduct = signal<Product | null>(null);
  readonly activeModalImage = signal<string>('');

  // Toast Feedback
  readonly toastMessage = signal<string>('');
  private toastTimeout: ReturnType<typeof setTimeout> | undefined;

  readonly adding = signal(false);
  constructor(private readonly productService: ProductService,
              private readonly cartService: CartService,
              public readonly auth: AuthService,
              private readonly router: Router) {}

  ngOnInit(): void {
    this.subscriptions.add(this.requests.pipe(
      switchMap(request => request === null ? EMPTY : timer(request.delay).pipe(
        switchMap(() => this.productService.searchProducts(request.filters)),
        catchError(() => {
          this.loadError.set('Unable to retrieve products. Please try again.');
          this.loading.set(false);
          return EMPTY;
        })
      ))
    ).subscribe(result => {
      this.products.set(result.content);
      this.totalElements.set(result.totalElements);
      this.totalPages.set(result.totalPages);
      this.page.set(result.page);
      this.loading.set(false);
    }));
    this.subscriptions.add(this.filters.valueChanges.subscribe(() => {
      this.page.set(0);
      this.queueSearch(300);
    }));
    this.loadProducts();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    if (this.toastTimeout) clearTimeout(this.toastTimeout);
  }

  loadProducts(): void {
    this.queueSearch(0);
  }

  applyFilters(): void {
    this.page.set(0);
    this.loadProducts();
  }

  private queueSearch(delay: number): void {
    this.products.set([]);
    this.totalElements.set(0);
    this.totalPages.set(0);
    this.loadError.set('');
    if (this.filters.invalid) {
      this.filters.markAllAsTouched();
      this.loading.set(false);
      this.requests.next(null);
      return;
    }
    const values = this.filters.getRawValue();
    this.loading.set(true);
    this.requests.next({ filters: {
      ...values,
      q: values.q.trim(),
      category: values.category.trim().toLowerCase(),
      page: this.page(),
    }, delay });
  }

  changePage(page: number): void {
    if (this.loading() || this.filters.invalid || page < 0 || page >= this.totalPages() || page === this.page()) return;
    this.page.set(page);
    this.loadProducts();
  }

  hasFilters(): boolean {
    const values = this.filters.getRawValue();
    return !!(values.q || values.category || values.minPrice != null || values.maxPrice != null
      || values.inStock || values.sort !== 'newest' || values.size !== 12);
  }

  clearSearch(): void {
    this.filters.reset({ q: '', category: '', minPrice: null, maxPrice: null,
      inStock: false, sort: 'newest', size: 12 }, { emitEvent: false });
    this.page.set(0);
    this.loadProducts();
  }

  openQuickView(product: Product): void {
    this.selectedProduct.set(product);
    const firstImg = product.images && product.images.length > 0 ? product.images[0] : '';
    this.activeModalImage.set(firstImg);
  }

  closeQuickView(): void {
    this.selectedProduct.set(null);
    this.activeModalImage.set('');
  }

  setActiveModalImage(url: string): void {
    this.activeModalImage.set(url);
  }

  addToOrder(product: Product): void {
    if (!this.auth.isLoggedIn()) { this.router.navigate(['/login']); return; }
    if (!this.auth.hasRole('CLIENT')) { this.showToast('Only clients can add items to a cart.'); return; }
    if (!product.id || product.quantity < 1 || this.adding()) return;
    this.adding.set(true);
    this.cartService.add(product.id).subscribe({
      next: () => { this.adding.set(false); this.showToast(`Item "${product.name}" added to cart.`); },
      error: e => { this.adding.set(false); this.showToast(e.error?.message || 'Unable to add to cart. Please retry.'); }
    });
  }

  showToast(msg: string): void {
    this.toastMessage.set(msg);
    if (this.toastTimeout) clearTimeout(this.toastTimeout);
    this.toastTimeout = setTimeout(() => {
      this.toastMessage.set('');
    }, 3500);
  }

  @HostListener('window:keydown.escape')
  onEscapePress(): void {
    if (this.selectedProduct()) {
      this.closeQuickView();
    }
  }

  getPrimaryImage(p: Product): string {
    if (p.images && p.images.length > 0 && p.images[0]) {
      return p.images[0];
    }
    return '';
  }

  onImageError(event: Event): void {
    const imgElement = event.target as HTMLImageElement;
    imgElement.style.display = 'none';
    const fallback = imgElement.nextElementSibling as HTMLElement;
    if (fallback) {
      fallback.style.display = 'flex';
    }
  }
}
