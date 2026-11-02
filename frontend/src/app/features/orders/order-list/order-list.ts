import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ORDER_STATUSES, OrderFilters, OrderPage } from '../../../core/models/order';
import { OrderScope, OrderService } from '../../../core/services/order';
import { shortId, statusBadge, statusLabel } from '../order-status';

export const PAGE_SIZE = 10;

/** Customer order history (/orders) and seller orders (/seller/orders), chosen by route data. */
@Component({
  selector: 'app-order-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './order-list.html',
})
export class OrderList implements OnInit {
  readonly statuses = ORDER_STATUSES;
  readonly statusLabel = statusLabel;
  readonly statusBadge = statusBadge;
  readonly shortId = shortId;

  readonly scope: OrderScope;
  readonly result = signal<OrderPage | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  filters: OrderFilters = { status: '', from: '', to: '', q: '' };
  private applied: OrderFilters = {};

  constructor(
    private readonly orders: OrderService,
    route: ActivatedRoute,
  ) {
    this.scope = route.snapshot.data['scope'] === 'seller' ? 'seller' : 'my';
  }

  get seller(): boolean {
    return this.scope === 'seller';
  }

  get filtered(): boolean {
    return Object.values(this.applied).some(Boolean);
  }

  ngOnInit(): void {
    this.load(0);
  }

  search(): void {
    const { from, to } = this.filters;
    if (from && to && from > to) {
      this.error.set('The start date must be on or before the end date.');
      return;
    }
    this.applied = { ...this.filters };
    this.load(0);
  }

  reset(): void {
    this.filters = { status: '', from: '', to: '', q: '' };
    this.applied = {};
    this.load(0);
  }

  load(page: number): void {
    this.busy.set(true);
    this.error.set('');
    this.orders.list(this.scope, this.applied, page, PAGE_SIZE).subscribe({
      next: result => {
        this.result.set(result);
        this.busy.set(false);
      },
      error: e => {
        this.error.set(e.error?.message || 'Unable to load orders. Please retry.');
        this.busy.set(false);
      },
    });
  }

  productNames(names: { productName: string }[]): string {
    const shown = names.slice(0, 3).map(item => item.productName).join(', ');
    return names.length > 3 ? `${shown} +${names.length - 3} more` : shown;
  }
}
