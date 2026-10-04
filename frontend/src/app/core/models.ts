export type AccountStatus = 'ACTIVE' | 'BLOCKED' | 'CLOSED';

export type SagaStatus =
  | 'STARTED'
  | 'DEBIT_PENDING'
  | 'DEBIT_FAILED'
  | 'FRAUD_CHECK_PENDING'
  | 'FRAUD_REJECTED'
  | 'CREDIT_PENDING'
  | 'CREDIT_FAILED'
  | 'COMPLETED'
  | 'COMPENSATING'
  | 'COMPENSATED'
  | 'FAILED';

export const TERMINAL_STATUSES: readonly SagaStatus[] = ['COMPLETED', 'COMPENSATED', 'FAILED'];

export interface Account {
  id: string;
  ownerName: string;
  currency: string;
  status: AccountStatus;
  balance: number;
  createdAt: string;
}

export interface CreateAccountRequest {
  ownerName: string;
  currency: string;
  openingBalance: number;
}

export interface Transfer {
  sagaId: string;
  transactionId: string;
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  status: SagaStatus;
  reason: string | null;
  history: string[];
  createdAt: string;
  updatedAt: string;
}

export interface TransferRequest {
  fromAccountId: string;
  toAccountId: string;
  amount: number;
}

export interface Notification {
  id: string;
  accountId: string;
  sagaId: string;
  amount: number;
  status: SagaStatus;
  message: string;
  occurredAt: string;
}

export interface FraudRules {
  largeAmountThreshold: number;
  veryLargeAmountThreshold: number;
  velocityWindowSeconds: number;
  velocityMaxTransactions: number;
  rejectScoreThreshold: number;
}

export interface FraudEvaluation {
  sagaId: string;
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  approved: boolean;
  riskScore: number;
  triggeredRules: string[];
  evaluatedAt: string;
}

/** One parsed line of a saga's history ("<instant> -> STATUS (note)"). */
export interface HistoryEntry {
  at: string;
  status: SagaStatus;
  note: string | null;
}

export function isTerminal(status: SagaStatus): boolean {
  return TERMINAL_STATUSES.includes(status);
}

export function parseHistory(lines: string[]): HistoryEntry[] {
  return lines.map((line) => {
    const match = /^(\S+) -> (\w+)(?: \((.*)\))?$/.exec(line);
    if (!match) {
      return { at: '', status: 'STARTED' as SagaStatus, note: line };
    }
    return { at: match[1], status: match[2] as SagaStatus, note: match[3] ?? null };
  });
}
