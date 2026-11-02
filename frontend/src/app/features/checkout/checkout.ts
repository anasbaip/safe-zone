import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CartService } from '../../core/services/cart';
import { CartData, CheckoutOrder, DeliveryAddress } from '../../core/models/cart';

@Component({ selector: 'app-checkout', standalone: true, imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './checkout.html' })
export class Checkout implements OnInit {
  readonly busy = signal(false);
  readonly error = signal('');
  readonly step = signal<'address' | 'review' | 'done'>('address');
  readonly cart = signal<CartData | null>(null);
  readonly order = signal<CheckoutOrder | null>(null);
  readonly pending = signal(false);
  address: DeliveryAddress = { fullName: '', phone: '', street: '', city: '', postalCode: '', country: '' };
  readonly fields = [
    { name: 'fullName', label: 'Full name', max: 100, autocomplete: 'name' },
    { name: 'phone', label: 'Phone', max: 30, autocomplete: 'tel' },
    { name: 'street', label: 'Street address', max: 200, autocomplete: 'street-address' },
    { name: 'city', label: 'City', max: 100, autocomplete: 'address-level2' },
    { name: 'postalCode', label: 'Postal code', max: 20, autocomplete: 'postal-code' },
    { name: 'country', label: 'Country', max: 100, autocomplete: 'country-name' }
  ] as const;
  private key = '';
  private token = '';
  constructor(private readonly carts: CartService) {}
  ngOnInit() { this.load(); }
  load() {
    this.busy.set(true); this.error.set('');
    this.carts.get().subscribe({ next: cart => {
      this.cart.set(cart);
      if (cart.pending) {
        this.address = { ...cart.pending.shippingAddress }; this.key = cart.pending.key;
        this.token = cart.pending.reviewToken; this.pending.set(true); this.step.set('review');
      }
      this.busy.set(false);
    }, error: e => this.fail(e) });
  }
  review(form: NgForm) {
    if (form.invalid || this.busy()) { form.control.markAllAsTouched(); return; }
    for (const field of this.fields) this.address[field.name] = this.address[field.name].trim();
    if (Object.values(this.address).some(v => !v)) { this.error.set('Complete all delivery fields.'); return; }
    this.busy.set(true); this.error.set('');
    this.carts.review().subscribe({ next: cart => {
      this.cart.set(cart); this.token = cart.reviewToken; this.key = crypto.randomUUID();
      this.step.set('review'); this.busy.set(false);
    }, error: e => this.fail(e) });
  }
  edit() { if (!this.pending()) this.step.set('address'); }
  confirm() {
    if (this.busy() || !this.cart()?.items.length) return;
    this.busy.set(true); this.error.set(''); this.pending.set(true);
    this.carts.checkout(this.address, this.token, this.key).subscribe({ next: order => {
      this.order.set(order); this.step.set('done'); this.pending.set(false); this.busy.set(false);
    }, error: e => {
      this.fail(e);
      // A definitive validation conflict before submission permits a fresh review.
      // An uncertain transport failure keeps the original request and idempotency key.
      if (e.status === 400 || e.status === 409) {
        this.carts.get().subscribe({ next: cart => {
          this.cart.set(cart);
          if (cart.pending) {
            this.address = { ...cart.pending.shippingAddress }; this.key = cart.pending.key;
            this.token = cart.pending.reviewToken;
          } else { this.pending.set(false); this.step.set('address'); }
        }, error: () => {} });
      }
    } });
  }
  private fail(e: { error?: { message?: string } }) {
    this.error.set(e.error?.message || 'Checkout is unavailable. Your cart has been retained. Please retry.'); this.busy.set(false);
  }
}
