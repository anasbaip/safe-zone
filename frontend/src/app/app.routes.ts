import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth-guard';
import { roleGuard } from './core/guards/role-guard';

export const routes: Routes = [
  { path: '', redirectTo: 'products', pathMatch: 'full' },
  { 
    path: 'login', 
    loadComponent: () => import('./features/auth/login/login').then(m => m.Login) 
  },
  { 
    path: 'register', 
    loadComponent: () => import('./features/auth/register/register').then(m => m.Register) 
  },
  { 
    path: 'products', 
    loadComponent: () => import('./features/products/product-list/product-list').then(m => m.ProductList) 
  },
  {
    path: 'profile',
    loadComponent: () => import('./features/profile/profile').then(m => m.Profile),
    canActivate: [authGuard]
  },
  { 
    path: 'seller/dashboard', 
    loadComponent: () => import('./features/seller/dashboard/seller-dashboard').then(m => m.SellerDashboard),
    canActivate: [authGuard, roleGuard('SELLER')]
  },
  { 
    path: 'seller/media', 
    loadComponent: () => import('./features/seller/media-manager/media-manager').then(m => m.MediaManager),
    canActivate: [authGuard, roleGuard('SELLER')]
  },
  { path: 'cart', loadComponent: () => import('./features/cart/cart').then(m => m.Cart), canActivate: [authGuard, roleGuard('CLIENT')] },
  { path: 'checkout', loadComponent: () => import('./features/checkout/checkout').then(m => m.Checkout), canActivate: [authGuard, roleGuard('CLIENT')] },
  {
    path: 'orders',
    loadComponent: () => import('./features/orders/order-list/order-list').then(m => m.OrderList),
    canActivate: [authGuard, roleGuard('CLIENT')],
    data: { scope: 'my' }
  },
  {
    path: 'orders/:id',
    loadComponent: () => import('./features/orders/order-details/order-details').then(m => m.OrderDetails),
    canActivate: [authGuard]
  },
  {
    path: 'seller/orders',
    loadComponent: () => import('./features/orders/order-list/order-list').then(m => m.OrderList),
    canActivate: [authGuard, roleGuard('SELLER')],
    data: { scope: 'seller' }
  },
  {
    path: '**',
    loadComponent: () => import('./features/error/not-found/not-found').then(m => m.NotFound)
  }
];