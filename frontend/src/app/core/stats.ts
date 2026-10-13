import { isTerminal, Transfer } from './models';

/** Transfers created on one calendar day (local time). */
export interface DayBucket {
  /** Local date, `yyyy-mm-dd`. */
  day: string;
  count: number;
  amount: number;
}

export type Outcome = 'COMPLETED' | 'COMPENSATED' | 'FAILED' | 'IN_PROGRESS';

export interface OutcomeShare {
  outcome: Outcome;
  label: string;
  tone: 'success' | 'warning' | 'danger' | 'info';
  count: number;
  /** Share of all transfers, 0-100. */
  percent: number;
}

const OUTCOMES: { outcome: Outcome; label: string; tone: OutcomeShare['tone'] }[] = [
  { outcome: 'COMPLETED', label: 'Terminés', tone: 'success' },
  { outcome: 'COMPENSATED', label: 'Remboursés', tone: 'warning' },
  { outcome: 'FAILED', label: 'Échoués', tone: 'danger' },
  { outcome: 'IN_PROGRESS', label: 'En cours', tone: 'info' },
];

/** One bucket per day for the last `days` days (oldest first, today last), empty days included. */
export function dailyVolume(transfers: Transfer[], days = 7, now = new Date()): DayBucket[] {
  const buckets = new Map<string, DayBucket>();
  for (let i = days - 1; i >= 0; i--) {
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);
    const day = localDay(date);
    buckets.set(day, { day, count: 0, amount: 0 });
  }
  for (const transfer of transfers) {
    const bucket = buckets.get(localDay(new Date(transfer.createdAt)));
    if (bucket) {
      bucket.count++;
      bucket.amount += transfer.amount;
    }
  }
  return [...buckets.values()];
}

/** How transfers ended, in a fixed order so colours never move between refreshes. */
export function outcomeBreakdown(transfers: Transfer[]): OutcomeShare[] {
  const counts = new Map<Outcome, number>();
  for (const transfer of transfers) {
    const outcome = outcomeOf(transfer);
    counts.set(outcome, (counts.get(outcome) ?? 0) + 1);
  }
  const total = transfers.length;
  return OUTCOMES.map((o) => {
    const count = counts.get(o.outcome) ?? 0;
    return { ...o, count, percent: total === 0 ? 0 : (count / total) * 100 };
  });
}

function outcomeOf(transfer: Transfer): Outcome {
  return isTerminal(transfer.status) ? (transfer.status as Outcome) : 'IN_PROGRESS';
}

function localDay(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
