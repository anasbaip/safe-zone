import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { API_BASE_URL } from '../api-config';
import { Role } from '../models/user';
import { AnalyticsData } from '../models/analytics';

@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  constructor(private readonly http: HttpClient) {}

  get(role: Role, from = '', to = '') {
    let params = new HttpParams();
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    return this.http.get<AnalyticsData>(`${API_BASE_URL}/orders/analytics/${role === 'SELLER' ? 'seller' : 'me'}`, { params });
  }
}
