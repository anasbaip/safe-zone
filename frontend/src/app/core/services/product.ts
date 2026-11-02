import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Product, ProductSearchFilters, ProductSearchResponse } from '../models/product';
import { API_BASE_URL } from '../api-config';

@Injectable({ providedIn: 'root' })
export class ProductService {
  private readonly API_URL = `${API_BASE_URL}/products`;

  constructor(private readonly http: HttpClient) {}

  getProducts(): Observable<Product[]> {
    return this.http.get<Product[]>(this.API_URL);
  }

  searchProducts(filters: ProductSearchFilters = {}): Observable<ProductSearchResponse> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(filters)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<ProductSearchResponse>(`${this.API_URL}/search`, { params });
  }

  getMyProducts(): Observable<Product[]> {
    return this.http.get<Product[]>(`${this.API_URL}/myProducts`);
  }

  getProductById(id: string): Observable<Product> {
    return this.http.get<Product>(`${this.API_URL}/${id}`);
  }

  createProduct(product: Product): Observable<Product> {
    return this.http.post<Product>(this.API_URL, product);
  }

  updateProduct(id: string, product: Product): Observable<Product> {
    return this.http.put<Product>(`${this.API_URL}/${id}`, product);
  }

  deleteProduct(id: string): Observable<void> {
    return this.http.delete<void>(`${this.API_URL}/${id}`);
  }
}
