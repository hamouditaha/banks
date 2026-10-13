import { Account, Transfer } from './models';

/** Test fixtures: sensible defaults, override only what the test is about. */
export function aTransfer(overrides: Partial<Transfer> = {}): Transfer {
  return {
    sagaId: 'saga-1',
    transactionId: 'tx-1',
    fromAccountId: 'acc-a',
    toAccountId: 'acc-b',
    amount: 100,
    status: 'COMPLETED',
    reason: null,
    history: [],
    createdAt: '2026-10-13T10:00:00Z',
    updatedAt: '2026-10-13T10:00:01Z',
    ...overrides,
  };
}

export function anAccount(overrides: Partial<Account> = {}): Account {
  return {
    id: 'acc-a',
    ownerName: 'Alice',
    currency: 'MAD',
    status: 'ACTIVE',
    balance: 1000,
    createdAt: '2026-10-01T09:00:00Z',
    ...overrides,
  };
}
