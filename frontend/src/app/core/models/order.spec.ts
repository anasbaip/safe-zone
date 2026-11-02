import { CANCELLABLE, NEXT_STATUSES, ORDER_STATUSES } from './order';

describe('order status rules', () => {
  it('defines next statuses for every status, using only known statuses', () => {
    expect(Object.keys(NEXT_STATUSES).sort()).toEqual([...ORDER_STATUSES].sort());
    for (const next of Object.values(NEXT_STATUSES)) {
      expect(next.every(status => ORDER_STATUSES.includes(status))).toBe(true);
    }
  });

  it('treats delivered and cancelled orders as final', () => {
    expect(NEXT_STATUSES.DELIVERED).toEqual([]);
    expect(NEXT_STATUSES.CANCELLED).toEqual([]);
  });

  it('lets customers cancel only before shipping', () => {
    expect(CANCELLABLE).toEqual(['PENDING', 'CONFIRMED']);
    expect(CANCELLABLE.every(status => NEXT_STATUSES[status].includes('CANCELLED'))).toBe(true);
  });
});
