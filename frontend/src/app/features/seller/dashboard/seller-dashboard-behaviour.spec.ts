import { FormBuilder } from '@angular/forms';
import { of, throwError } from 'rxjs';

import { SellerDashboard } from './seller-dashboard';
import { Product } from '../../../core/models/product';

const product: Product = { id: 'p', name: 'Chair', description: 'Wooden chair', price: 20, quantity: 1, images: ['https://old'], mediaIds: ['old'] };

function build(products: any, media: any) {
  return new SellerDashboard(new FormBuilder(), products, media);
}

function defaultMocks() {
  return {
    products: {
      getMyProducts: vi.fn(() => of([product])),
      createProduct: vi.fn(() => of(product)),
      updateProduct: vi.fn(() => of(product)),
      deleteProduct: vi.fn(() => of(undefined)),
    },
    media: {
      MAX_FILE_SIZE: 2097152,
      uploadImage: vi.fn(() => of({ id: 'new', url: 'https://new' })),
      deleteImage: vi.fn(() => of(undefined)),
    },
  };
}

describe('SellerDashboard behaviour', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('loads metrics and filters', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.ngOnInit();
    expect(c.totalListings()).toBe(1);
    expect(c.totalUnits()).toBe(1);
    expect(c.inStockCount()).toBe(1);
    expect(c.outOfStockCount()).toBe(0);
    c.filterQuery.set('chair');
    expect(c.filteredProducts().length).toBe(1);
    c.filterQuery.set('zzz');
    expect(c.filteredProducts().length).toBe(0);
  });

  it('reports a load failure', () => {
    const m = defaultMocks();
    m.products.getMyProducts.mockReturnValue(throwError(() => new Error('down')));
    const c = build(m.products, m.media);
    c.loadProducts();
    expect(c.statusMessage()).toContain('Failed');
  });

  it('opens and closes the create form', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.openCreateForm();
    expect(c.showForm()).toBe(true);
    expect(c.editingId()).toBeNull();
    c.closeForm();
    expect(c.showForm()).toBe(false);
  });

  it('edits an existing product', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.editProduct(product);
    expect(c.editingId()).toBe('p');
    expect(c.attachedMedia().length).toBe(1);
  });

  it('preserves categories while editing and supports older products', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.editProduct({ ...product, category: 'furniture' });
    expect(c.productForm.get('category')?.value).toBe('furniture');
    c.editProduct(product);
    expect(c.productForm.get('category')?.value).toBe('uncategorized');
    c.productForm.get('category')?.setValue('   ');
    expect(c.productForm.get('category')?.invalid).toBe(true);
    c.productForm.get('category')?.setValue('a'.repeat(81));
    expect(c.productForm.get('category')?.invalid).toBe(true);
  });

  it('rejects non-image uploads', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.uploadImage({ target: { files: [new File(['x'], 'a.txt', { type: 'text/plain' })], value: '' } } as unknown as Event);
    expect(c.statusMessage()).toContain('Only image files');
  });

  it('rejects oversized uploads', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    const big = new File([new ArrayBuffer(m.media.MAX_FILE_SIZE + 1)], 'b.png', { type: 'image/png' });
    c.uploadImage({ target: { files: [big], value: '' } } as unknown as Event);
    expect(c.statusMessage()).toContain('2 MB');
  });

  it('requires at least one image before saving', async () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.openCreateForm();
    c.productForm.setValue({ name: 'New', price: 1, quantity: 1, description: 'Description', category: 'furniture' });
    await c.saveProduct();
    expect(c.productForm.valid).toBe(true);
    expect(c.statusMessage()).toContain('image is required');
    expect(m.products.createProduct).not.toHaveBeenCalled();
  });

  it('creates a new product', async () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.openCreateForm();
    c.productForm.setValue({ name: 'New', price: 5, quantity: 2, description: 'Description', category: 'furniture' });
    c.uploadImage({ target: { files: [new File(['x'], 'p.png', { type: 'image/png' })], value: '' } } as unknown as Event);
    await c.saveProduct();
    expect(m.media.uploadImage).toHaveBeenCalledTimes(1);
    expect(m.products.createProduct).toHaveBeenCalledWith(expect.objectContaining({ name: 'New', mediaIds: ['new'] }));
    expect(c.statusMessage()).toContain('created');
  });

  it('does not save an invalid form', async () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.openCreateForm();
    await c.saveProduct();
    expect(m.products.createProduct).not.toHaveBeenCalled();
  });

  it('reports save failures', async () => {
    const m = defaultMocks();
    m.products.createProduct.mockReturnValue(throwError(() => ({ error: { message: 'nope' } })));
    const c = build(m.products, m.media);
    c.openCreateForm();
    c.productForm.setValue({ name: 'New', price: 5, quantity: 2, description: 'Description', category: 'furniture' });
    c.uploadImage({ target: { files: [new File(['x'], 'p.png', { type: 'image/png' })], value: '' } } as unknown as Event);
    await c.saveProduct();
    expect(c.statusMessage()).toBe('nope');
  });

  it('manages delete confirmation', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.requestDeleteProduct(product);
    expect(c.pendingDeleteProduct()?.id).toBe('p');
    c.confirmDelete();
    expect(m.products.deleteProduct).toHaveBeenCalledWith('p');
  });

  it('cancels delete', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.requestDeleteProduct(product);
    c.cancelDelete();
    expect(c.pendingDeleteProduct()).toBeNull();
  });

  it('reports delete failures', () => {
    const m = defaultMocks();
    m.products.deleteProduct.mockReturnValue(throwError(() => ({ error: { message: 'no' } })));
    const c = build(m.products, m.media);
    c.requestDeleteProduct(product);
    c.confirmDelete();
    expect(c.statusMessage()).toBe('no');
  });

  it('confirmDelete without a pending product is a no-op', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.confirmDelete();
    expect(m.products.deleteProduct).not.toHaveBeenCalled();
  });

  it('escape closes form and cancels delete', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    c.openCreateForm();
    c.requestDeleteProduct(product);
    c.onEscapePress();
    expect(c.pendingDeleteProduct()).toBeNull();
    expect(c.showForm()).toBe(false);
  });

  it('onThumbError hides the element', () => {
    const m = defaultMocks();
    const c = build(m.products, m.media);
    const img = document.createElement('img');
    c.onThumbError({ target: img } as unknown as Event);
    expect(img.style.display).toBe('none');
  });
});