import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, concatMap, from, map, of, toArray } from 'rxjs';
import { CANCELLABLE, NEXT_STATUSES, Order, OrderStatus } from '../../../core/models/order';
import { AuthService } from '../../../core/services/auth';
import { CartService } from '../../../core/services/cart';
import { OrderService } from '../../../core/services/order';
import { shortId, statusBadge, statusLabel } from '../order-status';

export const NOTE_MAX = 255;

/** One order: items, totals, address and status timeline. Sellers only receive their own items. */
@Component({
  selector: 'app-order-details',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './order-details.html',
})
export class OrderDetails implements OnInit, OnDestroy {
  readonly statusLabel = statusLabel;
  readonly statusBadge = statusBadge;
  readonly shortId = shortId;
  readonly noteMax = NOTE_MAX;

  readonly order = signal<Order | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly toast = signal('');
  readonly confirmingCancel = signal(false);
  readonly reordered = signal(false);
  nextStatus: OrderStatus | '' = '';
  note = '';
  readonly seller: boolean;
  private readonly id: string;
  private toastTimer?: ReturnType<typeof setTimeout>;

  constructor(
    private readonly orders: OrderService,
    private readonly carts: CartService,
    auth: AuthService,
    route: ActivatedRoute,
  ) {
    this.seller = auth.hasRole('SELLER');
    this.id = route.snapshot.paramMap.get('id') ?? '';
  }

  get backLink(): string {
    return this.seller ? '/seller/orders' : '/orders';
  }

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    clearTimeout(this.toastTimer);
  }

  load(): void {
    this.busy.set(true);
    this.error.set('');
    this.orders.get(this.id).subscribe({
      next: order => {
        this.order.set(order);
        this.busy.set(false);
      },
      error: e => this.fail(e, 'Unable to load this order. Please retry.'),
    });
  }

  canCancel(order: Order): boolean {
    return !this.seller && CANCELLABLE.includes(order.status);
  }

  cancel(): void {
    if (this.busy()) return;
    this.confirmingCancel.set(false);
    this.busy.set(true);
    this.error.set('');
    this.orders.cancel(this.id).subscribe({
      next: order => {
        this.order.set(order);
        this.busy.set(false);
        this.notify('Order cancelled. Nothing will be delivered or charged.');
      },
      error: e => this.fail(e, 'The order could not be cancelled. Please retry.'),
    });
  }

  /** Puts the products of this order back in the cart, at today's prices. */
  reorder(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.reordered.set(false);
    let firstProblem = '';
    this.orders
      .reorder(this.id)
      .pipe(
        concatMap(result => from(result.items)),
        concatMap(item =>
          this.carts.add(item.productId, item.quantity).pipe(
            map(() => true),
            catchError(e => {
              firstProblem ||= e.error?.message || 'unavailable';
              return of(false);
            }),
          ),
        ),
        toArray(),
      )
      .subscribe({
        next: added => {
          const count = added.filter(Boolean).length;
          this.busy.set(false);
          if (!count) {
            this.error.set(`None of these products could be added to your cart (${firstProblem}).`);
            return;
          }
          this.reordered.set(true);
          this.notify(
            count === added.length
              ? 'All products were added to your cart.'
              : `${count} of ${added.length} products were added to your cart. The others are unavailable.`,
          );
        },
        error: e => this.fail(e, 'The order could not be repeated. Please retry.'),
      });
  }

  /** Statuses a seller may move this order to; none for customers or finished orders. */
  nextStatuses(order: Order): readonly OrderStatus[] {
    return this.seller ? NEXT_STATUSES[order.status] : [];
  }

  updateStatus(): void {
    const status = this.nextStatus;
    if (this.busy() || !status) return;
    if (this.note.trim().length > NOTE_MAX) {
      this.error.set(`The note must be at most ${NOTE_MAX} characters.`);
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.orders.updateStatus(this.id, status, this.note).subscribe({
      next: order => {
        this.order.set(order);
        this.nextStatus = '';
        this.note = '';
        this.busy.set(false);
        this.notify(`Order marked as ${statusLabel(order.status).toLowerCase()}.`);
      },
      error: e => this.fail(e, 'The status could not be updated. Please retry.'),
    });
  }

  /** Newest change first. */
  timeline(order: Order) {
    return [...order.statusHistory].reverse();
  }

  protected notify(message: string): void {
    clearTimeout(this.toastTimer);
    this.toast.set(message);
    this.toastTimer = setTimeout(() => this.toast.set(''), 4000);
  }

  protected fail(e: { status?: number; error?: { message?: string } }, fallback: string): void {
    const notFound = e.status === 404 || e.status === 403;
    this.error.set(notFound ? 'This order does not exist or is not yours.' : e.error?.message || fallback);
    this.busy.set(false);
  }
}
