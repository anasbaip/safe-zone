export interface DeliveryAddress {
  fullName: string; phone: string; street: string; city: string; postalCode: string; country: string;
}
export interface CartItem {
  productId: string; name: string; image: string | null; quantity: number; availableQuantity: number;
  unitPrice: number; lineTotal: number; available: boolean;
}
export interface CartData {
  items: CartItem[]; totalAmount: number; reviewToken: string; checkoutPending: boolean;
  pending: { key: string; shippingAddress: DeliveryAddress; reviewToken: string } | null;
}
export interface CheckoutOrder { id: string; customerId: string; totalAmount: number; }
