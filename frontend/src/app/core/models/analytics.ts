export interface ProductSummary {
  productId: string;
  name: string;
  units: number;
  amount: number;
}

export interface CategorySummary {
  category: string;
  units: number;
  amount: number;
}

export interface MonthlySummary {
  month: string;
  orderCount: number;
  units: number;
  amount: number;
}

export interface AnalyticsData {
  from: string;
  to: string;
  countedStatus: 'DELIVERED';
  totalAmount: number;
  orderCount: number;
  totalUnits: number;
  products: ProductSummary[];
  categories: CategorySummary[];
  months: MonthlySummary[];
}
