import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AnalyticsService } from './analytics';

describe('Analytics API', () => {
  let http: HttpTestingController;
  let service: AnalyticsService;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(AnalyticsService);
  });
  afterEach(() => http.verify());

  it('uses the customer endpoint without a caller-supplied identity', () => {
    service.get('CLIENT').subscribe();
    const request = http.expectOne('/api/orders/analytics/me');
    expect(request.request.method).toBe('GET');
    request.flush({});
  });

  it('passes dates to the seller endpoint', () => {
    service.get('SELLER', '2026-01-01', '2026-01-31').subscribe();
    const request = http.expectOne('/api/orders/analytics/seller?from=2026-01-01&to=2026-01-31');
    request.flush({});
  });
});
