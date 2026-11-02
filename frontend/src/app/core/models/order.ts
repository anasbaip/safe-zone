import { DeliveryAddress } from './cart';

export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED';

export const ORDER_STATUSES: readonly OrderStatus[] = [
  'PENDING',
  'CONFIRMED',
  'SHIPPED',
  'DELIVERED',
  'CANCELLED',
];

/** Mirrors OrderStatus.allowedNext() in order-service; the server still enforces it. */
export const NEXT_STATUSES: Readonly<Record<OrderStatus, readonly OrderStatus[]>> = {
  PENDING: ['CONFIRMED', 'CANCELLED'],
  CONFIRMED: ['SHIPPED', 'CANCELLED'],
  SHIPPED: ['DELIVERED'],
  DELIVERED: [],
  CANCELLED: [],
};

/** Customers may cancel until the order ships. */
export const CANCELLABLE: readonly OrderStatus[] = ['PENDING', 'CONFIRMED'];

export interface OrderItem {
  productId: string;
  sellerId: string;
  productName: string;
  /** null for orders placed before categories were recorded. */
  category: string | null;
  imageUrl: string | null;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

export interface StatusChange {
  status: OrderStatus;
  changedAt: string;
  changedBy: string;
  note: string | null;
}

export interface Order {
  id: string;
  customerId: string;
  status: OrderStatus;
  items: OrderItem[];
  shippingAddress: DeliveryAddress;
  paymentMethod: string;
  totalItems: number;
  totalAmount: number;
  statusHistory: StatusChange[];
  createdAt: string;
  updatedAt: string;
}

export interface OrderPage {
  content: Order[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Query of GET /orders/my and /orders/seller; dates are yyyy-MM-dd. */
export interface OrderFilters {
  status?: OrderStatus | '';
  from?: string;
  to?: string;
  q?: string;
}

export interface ReorderResult {
  sourceOrderId: string;
  items: { productId: string; quantity: number }[];
}
