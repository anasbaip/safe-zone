import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { AnalyticsDashboard } from './analytics-dashboard';
import { AnalyticsService } from '../../core/services/analytics';
import { AnalyticsData } from '../../core/models/analytics';

const report: AnalyticsData = {
  from: '2026-01-01', to: '2026-03-31', countedStatus: 'DELIVERED',
  totalAmount: 45, orderCount: 2, totalUnits: 3,
  products: [{ productId: 'p', name: 'Chair snapshot', units: 3, amount: 45 }],
  categories: [{ category: 'furniture', units: 3, amount: 45 }],
  months: [
    { month: '2026-01', orderCount: 1, units: 1, amount: 15 },
    { month: '2026-02', orderCount: 0, units: 0, amount: 0 },
    { month: '2026-03', orderCount: 1, units: 2, amount: 30 }
  ]
};

describe('Analytics dashboard', () => {
  let api: { get: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    api = { get: vi.fn(() => of(report)) };
    TestBed.configureTestingModule({ imports: [AnalyticsDashboard], providers: [{ provide: AnalyticsService, useValue: api }] });
  });

  function render(role: 'CLIENT' | 'SELLER' = 'CLIENT') {
    const fixture = TestBed.createComponent(AnalyticsDashboard);
    fixture.componentRef.setInput('audience', role);
    fixture.detectChanges();
    return fixture;
  }

  it('renders real customer totals, rankings and accessible monthly values', () => {
    const fixture = render();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Total spent');
    expect(text).toContain('$45.00');
    expect(text).toContain('Chair snapshot');
    expect(text).toContain('furniture');
    expect(text).toContain('2026-02');
    expect(text).toContain('Delivered orders only');
    expect(fixture.componentInstance.range.value).toEqual({ from: report.from, to: report.to });
  });

  it('uses seller analytics and sales labels', () => {
    const fixture = render('SELLER');
    expect(api.get).toHaveBeenCalledWith('SELLER', '', '');
    expect(fixture.nativeElement.textContent).toContain('Revenue');
    expect(fixture.nativeElement.textContent).toContain('Units sold');
    expect(fixture.nativeElement.textContent).toContain('Sales and order trends');
  });

  it('shows loading and empty states without sample figures', () => {
    const pending = new Subject<AnalyticsData>();
    api.get.mockReturnValue(pending);
    const fixture = render();
    expect(fixture.nativeElement.textContent).toContain('Loading analytics');
    pending.next({ ...report, totalAmount: 0, orderCount: 0, totalUnits: 0, products: [], categories: [] });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No delivered orders');
    expect(fixture.nativeElement.textContent).not.toContain('Chair snapshot');
  });

  it('shows errors and allows a retry', () => {
    api.get.mockReturnValueOnce(throwError(() => ({ error: { message: 'Service unavailable' } })));
    const fixture = render();
    expect(fixture.nativeElement.textContent).toContain('Service unavailable');
    const button = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(item => item.textContent?.includes('Retry'))!;
    button.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('$45.00');
    expect(fixture.componentInstance.error()).toBe('');
  });

  it('validates ranges and submits selected dates', () => {
    const fixture = render();
    const component = fixture.componentInstance;
    component.range.setValue({ from: '2026-03-01', to: '2026-01-01' });
    component.load();
    expect(api.get).toHaveBeenCalledTimes(1);
    component.range.setValue({ from: '2020-01-01', to: '2026-01-01' });
    expect(component.range.invalid).toBe(true);
    component.range.setValue({ from: '2026-01-01', to: '2026-01-31' });
    component.load();
    expect(api.get).toHaveBeenLastCalledWith('CLIENT', '2026-01-01', '2026-01-31');
  });

  it('cancels old requests and releases subscriptions on destruction', () => {
    const pending = new Subject<AnalyticsData>();
    api.get.mockReturnValueOnce(pending);
    const fixture = render();
    fixture.componentInstance.load();
    pending.next({ ...report, totalAmount: 999 });
    expect(fixture.componentInstance.data()?.totalAmount).toBe(45);
    expect(fixture.componentInstance.width(10, [20, 10])).toBe(50);
    expect(fixture.componentInstance.width(0, [])).toBe(0);
    fixture.destroy();
  });

  it('uses a fallback for errors without a response body', () => {
    api.get.mockReturnValue(throwError(() => ({})));
    const fixture = render();
    expect(fixture.nativeElement.textContent).toContain('Unable to load analytics');
  });
});
