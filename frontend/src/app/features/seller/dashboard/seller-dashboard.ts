import { Component, OnInit, OnDestroy, signal, computed, HostListener } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, FormsModule, Validators } from '@angular/forms';
import { ProductService } from '../../../core/services/product';
import { MediaService } from '../../../core/services/media';
import { Product } from '../../../core/models/product';

@Component({
  selector: 'app-seller-dashboard',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FormsModule],
  templateUrl: './seller-dashboard.html',
  styleUrl: './seller-dashboard.css'
})
export class SellerDashboard implements OnInit, OnDestroy {
  readonly products = signal<Product[]>([]);
  productForm: FormGroup;
  readonly showForm = signal<boolean>(false);
  readonly editingId = signal<string | null>(null);
  readonly attachedMedia = signal<{ id?: string; url: string; file?: File }[]>([]);
  readonly uploading = signal<boolean>(false);
  readonly saving = signal<boolean>(false);
  readonly statusMessage = signal<string>('');
  readonly statusType = signal<'success' | 'danger' | 'info'>('info');

  private readonly draftMediaIds = new Set<string>();
  private readonly previewUrls = new Set<string>();

  // Search in seller table
  readonly filterQuery = signal<string>('');

  // Accessible in-app delete modal (R-26, R-32)
  readonly pendingDeleteProduct = signal<Product | null>(null);

  constructor(
    private readonly fb: FormBuilder,
    private readonly productService: ProductService,
    private readonly mediaService: MediaService
  ) {
    this.productForm = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(2)]],
      category: ['uncategorized', [Validators.required, Validators.maxLength(80), Validators.pattern(/\S/)]],
      price: [null, [Validators.required, Validators.min(0.01)]],
      quantity: [1, [Validators.required, Validators.min(0)]],
      description: ['', [Validators.required, Validators.minLength(5)]]
    });
  }

  ngOnInit(): void {
    this.loadProducts();
  }

  loadProducts(): void {
    this.productService.getMyProducts().subscribe({
      next: (data) => this.products.set(data || []),
      error: () => this.showNotification('Failed to retrieve catalog listings.', 'danger')
    });
  }

  // Real dashboard metrics (R-17 - genuine data only, no fabricated numbers)
  readonly totalListings = computed(() => this.products().length);
  readonly totalUnits = computed(() => this.products().reduce((acc, p) => acc + (p.quantity || 0), 0));
  readonly inStockCount = computed(() => this.products().filter(p => (p.quantity || 0) > 0).length);
  readonly outOfStockCount = computed(() => this.products().filter(p => (p.quantity || 0) === 0).length);

  // Filtered seller products
  readonly filteredProducts = computed(() => {
    const q = this.filterQuery().trim().toLowerCase();
    if (!q) return this.products();
    return this.products().filter(p =>
      p.name.toLowerCase().includes(q) ||
      p.description?.toLowerCase().includes(q)
    );
  });

  openCreateForm(): void {
    if (this.saving()) return;
    this.discardDraft();
    this.editingId.set(null);
    this.attachedMedia.set([]);
    this.productForm.reset({ price: null, quantity: 1, name: '', description: '', category: 'uncategorized' });
    this.showForm.set(true);
  }

  editProduct(product: Product): void {
    if (this.saving()) return;
    this.discardDraft();
    this.editingId.set(product.id || null);
    const media = (product.images || []).map((url, index) => ({
      url,
      id: product.mediaIds?.[index]
    }));
    this.attachedMedia.set(media);
    this.productForm.patchValue({
      name: product.name,
      category: product.category || 'uncategorized',
      price: product.price,
      quantity: product.quantity,
      description: product.description
    });
    this.showForm.set(true);
  }

  closeForm(): void {
    if (this.saving()) return;
    this.discardDraft();
    this.showForm.set(false);
    this.editingId.set(null);
  }

  ngOnDestroy(): void {
    if (!this.saving()) this.discardDraft();
  }

  private deleteDraftImage(id: string): void {
    this.mediaService.deleteImage(id).subscribe({
      error: () => this.showNotification('Could not remove an unsaved photo. Remove it from your media library.', 'danger')
    });
  }

  private discardDraft(): void {
    this.draftMediaIds.forEach(id => this.deleteDraftImage(id));
    this.draftMediaIds.clear();
    this.previewUrls.forEach(url => URL.revokeObjectURL(url));
    this.previewUrls.clear();
    this.attachedMedia.set([]);
  }

  uploadImage(event: Event): void {
    if (this.saving()) return;
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      this.showNotification('Only image files are permitted.', 'danger');
      return;
    }
    if (file.size > this.mediaService.MAX_FILE_SIZE) {
      this.showNotification('File exceeds the 2 MB maximum limit.', 'danger');
      return;
    }
    const url = URL.createObjectURL(file);
    this.previewUrls.add(url);
    this.attachedMedia.update(list => [...list, { url, file }]);
  }

  removeImage(index: number): void {
    if (this.saving()) return;
    const image = this.attachedMedia()[index];
    if (image?.id && this.draftMediaIds.delete(image.id)) this.deleteDraftImage(image.id);
    if (image && this.previewUrls.delete(image.url)) URL.revokeObjectURL(image.url);
    this.attachedMedia.update(list => list.filter((_, i) => i !== index));
  }

  async saveProduct(): Promise<void> {
    if (this.saving()) return;
    if (this.productForm.invalid) {
      this.productForm.markAllAsTouched();
      return;
    }
    if (this.attachedMedia().length === 0) {
      this.showNotification('At least one product image is required.', 'danger');
      return;
    }
    const currentId = this.editingId();
    const values = this.productForm.getRawValue();
    this.saving.set(true);
    this.productForm.disable();
    try {
      for (const image of this.attachedMedia()) {
        if (!image.file) continue;
        this.uploading.set(true);
        const result = await firstValueFrom(this.mediaService.uploadImage(image.file));
        this.draftMediaIds.add(result.id);
        this.attachedMedia.update(list => list.map(item => item === image
          ? { id: result.id, url: result.url } : item));
        URL.revokeObjectURL(image.url);
        this.previewUrls.delete(image.url);
      }
      this.uploading.set(false);
      const mediaIds = this.attachedMedia().map(image => image.id).filter((id): id is string => !!id);
      if (mediaIds.length !== this.attachedMedia().length) {
        throw new Error('Each product image must have a media ID before saving.');
      }
      const payload: Product = { ...values, category: values.category.trim().toLowerCase(), mediaIds };
      await firstValueFrom(currentId
        ? this.productService.updateProduct(currentId, payload)
        : this.productService.createProduct(payload));
      this.draftMediaIds.clear();
      this.saving.set(false);
      this.closeForm();
      this.showNotification(currentId ? 'Product details updated successfully.' : 'New product created successfully.', 'success');
      this.loadProducts();
    } catch (err: any) {
      this.showNotification(err.error?.message || err.message || 'Failed to save product.', 'danger');
    } finally {
      this.uploading.set(false);
      this.saving.set(false);
      this.productForm.enable();
    }
  }

  requestDeleteProduct(product: Product): void {
    this.pendingDeleteProduct.set(product);
  }

  cancelDelete(): void {
    this.pendingDeleteProduct.set(null);
  }

  confirmDelete(): void {
    const p = this.pendingDeleteProduct();
    if (!p?.id) return;

    this.productService.deleteProduct(p.id).subscribe({
      next: () => {
        this.showNotification(`Product "${p.name}" deleted.`, 'success');
        this.pendingDeleteProduct.set(null);
        this.loadProducts();
      },
      error: (err) => {
        this.showNotification(err.error?.message || 'Failed to delete product.', 'danger');
        this.pendingDeleteProduct.set(null);
      }
    });
  }

  showNotification(msg: string, type: 'success' | 'danger' | 'info' = 'info'): void {
    this.statusMessage.set(msg);
    this.statusType.set(type);
    setTimeout(() => {
      this.statusMessage.set('');
    }, 4000);
  }

  @HostListener('window:keydown.escape')
  onEscapePress(): void {
    if (this.pendingDeleteProduct()) {
      this.cancelDelete();
    }
    if (this.showForm()) {
      this.closeForm();
    }
  }

  onThumbError(event: Event): void {
    const el = event.target as HTMLImageElement;
    el.style.display = 'none';
  }
}
