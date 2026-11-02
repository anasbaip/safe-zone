import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../api-config';
import { Order, OrderFilters, OrderPage, OrderStatus, ReorderResult } from '../models/order';

export type OrderScope = 'my' | 'seller';

@Injectable({ providedIn: 'root' })
export class OrderService {
  private readonly url = `${API_BASE_URL}/orders`;

  constructor(private readonly http: HttpClient) {}

  /** Customer orders ('my') or orders containing the seller's products ('seller'), newest first. */
  list(scope: OrderScope, filters: OrderFilters = {}, page = 0, size = 10): Observable<OrderPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const [key, value] of Object.entries(filters)) {
      const text = typeof value === 'string' ? value.trim() : '';
      if (text) params = params.set(key, text);
    }
    return this.http.get<OrderPage>(`${this.url}/${scope}`, { params });
  }

  get(id: string): Observable<Order> {
    return this.http.get<Order>(`${this.url}/${encodeURIComponent(id)}`);
  }

  cancel(id: string): Observable<Order> {
    return this.http.patch<Order>(`${this.url}/${encodeURIComponent(id)}/cancel`, {});
  }

  reorder(id: string): Observable<ReorderResult> {
    return this.http.post<ReorderResult>(`${this.url}/${encodeURIComponent(id)}/reorder`, {});
  }

  updateStatus(id: string, status: OrderStatus, note?: string): Observable<Order> {
    const trimmed = note?.trim();
    return this.http.patch<Order>(
      `${this.url}/${encodeURIComponent(id)}/status`,
      trimmed ? { status, note: trimmed } : { status },
    );
  }
}
