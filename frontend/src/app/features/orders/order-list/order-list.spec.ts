import { of, throwError } from 'rxjs';
import { ActivatedRoute } from '@angular/router';
import { Order, OrderPage } from '../../../core/models/order';
import { OrderService } from '../../../core/services/order';
import { OrderList, PAGE_SIZE } from './order-list';

describe('OrderList', () => {
  const order = { id: 'abc123def456', status: 'PENDING', items: [] } as unknown as Order;
  const page: OrderPage = { content: [order], page: 0, size: PAGE_SIZE, totalElements: 1, totalPages: 1 };
  let api: { list: ReturnType<typeof vi.fn> };

  const create = (scope?: string) =>
    new OrderList(
      api as unknown as OrderService,
      { snapshot: { data: scope ? { scope } : {} } } as unknown as ActivatedRoute,
    );

  beforeEach(() => {
    api = { list: vi.fn(() => of(page)) };
  });

  it('loads the first page of the customer orders by default', () => {
    const list = create();
    list.ngOnInit();
    expect(list.seller).toBe(false);
    expect(api.list).toHaveBeenCalledWith('my', {}, 0, PAGE_SIZE);
    expect(list.result()).toEqual(page);
    expect(list.busy()).toBe(false);
  });

  it('loads seller orders when the route says so', () => {
    const list = create('seller');
    list.ngOnInit();
    expect(list.seller).toBe(true);
    expect(api.list).toHaveBeenCalledWith('seller', {}, 0, PAGE_SIZE);
  });

  it('applies filters from the first page and resets them', () => {
    const list = create();
    list.filters = { status: 'SHIPPED', from: '2026-01-01', to: '2026-01-31', q: 'head' };
    list.search();
    expect(api.list).toHaveBeenLastCalledWith('my', list.filters, 0, PAGE_SIZE);
    expect(list.filtered).toBe(true);

    list.load(2);
    expect(api.list).toHaveBeenLastCalledWith('my', list.filters, 2, PAGE_SIZE);

    list.reset();
    expect(list.filters).toEqual({ status: '', from: '', to: '', q: '' });
    expect(api.list).toHaveBeenLastCalledWith('my', {}, 0, PAGE_SIZE);
    expect(list.filtered).toBe(false);
  });

  it('rejects a start date after the end date without calling the server', () => {
    const list = create();
    list.filters = { from: '2026-02-01', to: '2026-01-01' };
    list.search();
    expect(api.list).not.toHaveBeenCalled();
    expect(list.error()).toBe('The start date must be on or before the end date.');
  });

  it.each([
    [{ error: { message: 'from date must not be after to date' } }, 'from date must not be after to date'],
    [{}, 'Unable to load orders. Please retry.'],
  ])('shows load errors', (failure, message) => {
    api.list.mockReturnValue(throwError(() => failure));
    const list = create();
    list.ngOnInit();
    expect(list.error()).toBe(message);
    expect(list.busy()).toBe(false);
  });

  it('summarises product names and formats statuses', () => {
    const list = create();
    const names = ['A', 'B', 'C', 'D', 'E'].map(productName => ({ productName }));
    expect(list.productNames(names.slice(0, 2))).toBe('A, B');
    expect(list.productNames(names)).toBe('A, B, C +2 more');
    expect(list.statusLabel('SHIPPED')).toBe('Shipped');
    expect(list.statusBadge('CANCELLED')).toBe('buy-badge buy-badge-danger');
    expect(list.shortId(order.id)).toBe('23DEF456');
  });
});
