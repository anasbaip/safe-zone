import { FormBuilder } from '@angular/forms';
import { of, Subject, throwError } from 'rxjs';
import { SellerDashboard } from './seller-dashboard';

describe('Product draft photos', () => {
  let component: SellerDashboard;
  let products: any;
  let media: any;
  const product = { id: 'p', name: 'Chair', description: 'Wooden chair', price: 20, quantity: 1, images: ['https://old'], mediaIds: ['old'] };
  const uploaded = { id: 'new', url: 'https://new' };
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    products = { getMyProducts: vi.fn(() => of([product])), updateProduct: vi.fn(() => of(product)) };
    media = { MAX_FILE_SIZE: 2097152, uploadImage: vi.fn(() => of(uploaded)), deleteImage: vi.fn(() => of(undefined)) };
    component = new SellerDashboard(new FormBuilder(), products, media);
    component.editProduct(product);
  });
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); });
  function selectPhoto() {
    component.uploadImage({ target: { files: [new File(['photo'], 'photo.png', { type: 'image/png' })], value: '' } } as unknown as Event);
  }
  it('cancels without uploading or changing existing photos', () => {
    selectPhoto();
    component.closeForm();
    expect(media.uploadImage).not.toHaveBeenCalled();
    expect(products.updateProduct).not.toHaveBeenCalled();
    expect(media.deleteImage).not.toHaveBeenCalled();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview');
    component.editProduct(product);
    expect(component.attachedMedia()).toEqual([{ id: 'old', url: 'https://old' }]);
  });
  it('uploads on Save and retains the saved photo', async () => {
    selectPhoto();
    await component.saveProduct();
    expect(products.updateProduct).toHaveBeenCalledWith('p', expect.objectContaining({ mediaIds: ['old', 'new'] }));
    expect(media.deleteImage).not.toHaveBeenCalled();
    expect(component.showForm()).toBe(false);
  });
  it('blocks cancellation and duplicate saves during upload', async () => {
    const upload = new Subject<any>();
    media.uploadImage.mockReturnValue(upload);
    selectPhoto();
    const saving = component.saveProduct();
    component.onEscapePress();
    await component.saveProduct();
    expect(component.showForm()).toBe(true);
    expect(media.uploadImage).toHaveBeenCalledTimes(1);
    upload.next(uploaded);
    upload.complete();
    await saving;
  });
  it('deletes only new uploads after a failed save is canceled', async () => {
    products.updateProduct.mockReturnValue(throwError(() => new Error('Save failed')));
    selectPhoto();
    await component.saveProduct();
    expect(component.saving()).toBe(false);
    component.closeForm();
    expect(media.deleteImage).toHaveBeenCalledExactlyOnceWith('new');
  });
  it('does not upload a removed preview', async () => {
    selectPhoto();
    component.removeImage(1);
    await component.saveProduct();
    expect(media.uploadImage).not.toHaveBeenCalled();
    expect(products.updateProduct).toHaveBeenCalledWith('p', expect.objectContaining({ mediaIds: ['old'] }));
  });
  it('shows a safe storage failure and keeps the draft without saving a product', async () => {
    media.uploadImage.mockReturnValue(throwError(() => ({ status: 502,
      error: { message: 'Image storage is unavailable. Please retry or contact support.' } })));
    selectPhoto();
    await component.saveProduct();
    expect(component.statusMessage()).toBe('Image storage is unavailable. Please retry or contact support.');
    expect(component.showForm()).toBe(true);
    expect(component.saving()).toBe(false);
    expect(products.updateProduct).not.toHaveBeenCalled();
    expect(media.deleteImage).not.toHaveBeenCalled();
  });
});
