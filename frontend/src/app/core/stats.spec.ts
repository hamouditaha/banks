import { dailyVolume, outcomeBreakdown } from './stats';
import { aTransfer } from './testing';

describe('dashboard stats', () => {
  // Local noon, so local-day bucketing doesn't depend on the machine's time zone.
  const now = new Date(2026, 9, 13, 12, 0, 0);
  const at = (day: number, hour = 12) => new Date(2026, 9, day, hour).toISOString();

  it('buckets transfers per local day over the window, oldest first, empty days included', () => {
    const buckets = dailyVolume(
      [
        aTransfer({ createdAt: at(13), amount: 50 }),
        aTransfer({ createdAt: at(13, 8), amount: 25 }),
        aTransfer({ createdAt: at(11), amount: 10 }),
        aTransfer({ createdAt: at(1), amount: 999 }), // outside the 7-day window
      ],
      7,
      now,
    );

    expect(buckets.map((b) => b.day)).toEqual([
      '2026-10-07', '2026-10-08', '2026-10-09', '2026-10-10', '2026-10-11', '2026-10-12', '2026-10-13',
    ]);
    expect(buckets[6]).toEqual({ day: '2026-10-13', count: 2, amount: 75 });
    expect(buckets[4].count).toBe(1);
    expect(buckets.reduce((sum, b) => sum + b.count, 0)).toBe(3);
  });

  it('groups non-terminal sagas as in progress and keeps a fixed order', () => {
    const shares = outcomeBreakdown([
      aTransfer({ status: 'COMPLETED' }),
      aTransfer({ status: 'COMPLETED' }),
      aTransfer({ status: 'COMPENSATED' }),
      aTransfer({ status: 'CREDIT_PENDING' }),
    ]);

    expect(shares.map((s) => [s.outcome, s.count])).toEqual([
      ['COMPLETED', 2],
      ['COMPENSATED', 1],
      ['FAILED', 0],
      ['IN_PROGRESS', 1],
    ]);
    expect(shares[0].percent).toBe(50);
  });

  it('returns zero shares when there is no transfer', () => {
    expect(outcomeBreakdown([]).every((s) => s.count === 0 && s.percent === 0)).toBeTrue();
  });
});
