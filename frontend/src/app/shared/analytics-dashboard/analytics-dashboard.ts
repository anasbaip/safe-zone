import { Component, Input, OnChanges, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { Subscription } from 'rxjs';
import { AnalyticsService } from '../../core/services/analytics';
import { AnalyticsData } from '../../core/models/analytics';
import { Role } from '../../core/models/user';

export function dateRange(control: AbstractControl): ValidationErrors | null {
  const { from, to } = control.value;
  if (!from || !to) return null;
  const days = (Date.parse(to) - Date.parse(from)) / 86400000;
  return !Number.isFinite(days) || days < 0 || days > 365 ? { dateRange: true } : null;
}

@Component({
  selector: 'app-analytics-dashboard',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './analytics-dashboard.html',
  styleUrl: './analytics-dashboard.css'
})
export class AnalyticsDashboard implements OnChanges, OnDestroy {
  @Input() audience: Role = 'CLIENT';
  readonly data = signal<AnalyticsData | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly range = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true })
  }, { validators: dateRange });
  private request?: Subscription;

  constructor(private readonly analytics: AnalyticsService) {}

  ngOnChanges() { this.load(); }
  ngOnDestroy() { this.request?.unsubscribe(); }

  load() {
    if (this.range.invalid) { this.range.markAllAsTouched(); return; }
    this.request?.unsubscribe();
    this.loading.set(true);
    this.error.set('');
    this.data.set(null);
    const { from, to } = this.range.getRawValue();
    this.request = this.analytics.get(this.audience, from, to).subscribe({
      next: data => {
        this.data.set(data);
        this.range.patchValue({ from: data.from, to: data.to });
        this.loading.set(false);
      },
      error: e => {
        this.error.set(e.error?.message || 'Unable to load analytics. Please try again.');
        this.loading.set(false);
      }
    });
  }

  width(value: number, values: number[]) {
    const max = Math.max(0, ...values);
    return max > 0 ? Math.max(0, Math.min(100, value / max * 100)) : 0;
  }

  get categoryUnits() { return this.data()?.categories.map(c => c.units) ?? []; }
  get monthlyAmounts() { return this.data()?.months.map(m => m.amount) ?? []; }
}
