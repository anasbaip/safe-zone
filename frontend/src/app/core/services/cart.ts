import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { API_BASE_URL } from '../api-config';
import { CartData, CheckoutOrder, DeliveryAddress } from '../models/cart';

@Injectable({ providedIn: 'root' })
export class CartService {
  private readonly url = `${API_BASE_URL}/cart`;
  constructor(private readonly http: HttpClient) {}
  get() { return this.http.get<CartData>(this.url); }
  add(productId: string, quantity = 1) { return this.http.post<CartData>(`${this.url}/items`, { productId, quantity }); }
  update(productId: string, quantity: number) {
    return this.http.put<CartData>(`${this.url}/items/${encodeURIComponent(productId)}`, { quantity });
  }
  remove(productId: string) { return this.http.delete<CartData>(`${this.url}/items/${encodeURIComponent(productId)}`); }
  clear() { return this.http.delete<CartData>(this.url); }
  review() { return this.http.post<CartData>(`${this.url}/review`, {}); }
  checkout(shippingAddress: DeliveryAddress, reviewToken: string, key: string) {
    return this.http.post<CheckoutOrder>(`${this.url}/checkout`, { shippingAddress, reviewToken },
      { headers: { 'Idempotency-Key': key } });
  }
}
