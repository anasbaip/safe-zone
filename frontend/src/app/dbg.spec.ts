import { FormBuilder } from '@angular/forms';
import { of } from 'rxjs';
import { SellerDashboard } from '../../src/app/features/seller/dashboard/seller-dashboard';

describe('dbg', () => {
  it('valid', () => {
    const products:any={getMyProducts:()=>of([]),createProduct:()=>of({})};
    const media:any={MAX_FILE_SIZE:2097152,uploadImage:()=>of({id:'n',url:'u'})};
    const c=new SellerDashboard(new FormBuilder(),products,media);
    c.openCreateForm();
    c.productForm.setValue({name:'New',price:5,quantity:2,description:'desc',category:'furniture'});
    console.log('valid?', c.productForm.valid, c.productForm.value, JSON.stringify(c.productForm.errors));
  });
});
