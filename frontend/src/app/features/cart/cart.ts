import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { CartService } from '../../core/services/cart';
import { CartData } from '../../core/models/cart';

@Component({ selector: 'app-cart', standalone: true, imports: [CommonModule, RouterLink],
  templateUrl: './cart.html' })
export class Cart implements OnInit {
  readonly data = signal<CartData | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  constructor(private readonly carts: CartService) {}
  ngOnInit() { this.load(); }
  load() { this.run(this.carts.get()); }
  update(id: string, raw: string) {
    const n = Number(raw);
    if (!Number.isInteger(n) || n < 1 || n > 100) { this.error.set('Quantity must be a whole number from 1 to 100.'); return; }
    this.run(this.carts.update(id, n));
  }
  remove(id: string) { this.run(this.carts.remove(id)); }
  clear() { this.run(this.carts.clear()); }
  run(request: Observable<CartData>) {
    if (this.busy()) return;
    this.busy.set(true); this.error.set('');
    request.subscribe({ next: data => { this.data.set(data); this.busy.set(false); },
      error: e => { this.error.set(e.error?.message || 'Unable to update your cart. Please retry.'); this.busy.set(false); } });
  }
  canCheckout() { return !!this.data()?.items.length && this.data()!.items.every(i => i.available); }
}
