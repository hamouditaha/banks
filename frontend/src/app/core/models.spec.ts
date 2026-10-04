import { isTerminal, parseHistory } from './models';

describe('saga history helpers', () => {
  it('parses status lines with and without a note', () => {
    const entries = parseHistory([
      '2026-10-04T10:00:00Z -> STARTED',
      '2026-10-04T10:00:01Z -> DEBIT_PENDING (requesting debit from abc)',
      '2026-10-04T10:00:02Z -> FRAUD_REJECTED (fraud rejected (score=100, rules=[BLACKLISTED_ACCOUNT]))',
    ]);

    expect(entries[0]).toEqual({ at: '2026-10-04T10:00:00Z', status: 'STARTED', note: null });
    expect(entries[1].note).toBe('requesting debit from abc');
    expect(entries[2].status).toBe('FRAUD_REJECTED');
    expect(entries[2].note).toBe('fraud rejected (score=100, rules=[BLACKLISTED_ACCOUNT])');
  });

  it('keeps unparseable lines as notes', () => {
    expect(parseHistory(['garbage'])[0].note).toBe('garbage');
  });

  it('recognises terminal statuses', () => {
    expect(isTerminal('COMPLETED')).toBeTrue();
    expect(isTerminal('COMPENSATED')).toBeTrue();
    expect(isTerminal('FAILED')).toBeTrue();
    expect(isTerminal('CREDIT_PENDING')).toBeFalse();
  });
});
