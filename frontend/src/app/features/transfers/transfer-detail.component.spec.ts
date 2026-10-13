import { SagaStatus } from '../../core/models';
import { buildSteps } from './transfer-detail.component';

describe('buildSteps (saga stepper)', () => {
  const states = (status: SagaStatus, seen: SagaStatus[]) =>
    buildSteps(status, new Set(seen)).map((s) => s.state);

  it('marks the running step as current', () => {
    expect(states('FRAUD_CHECK_PENDING', ['STARTED', 'DEBIT_PENDING', 'FRAUD_CHECK_PENDING'])).toEqual([
      'done',
      'current',
      'pending',
    ]);
  });

  it('marks every step done on success', () => {
    expect(
      states('COMPLETED', ['STARTED', 'DEBIT_PENDING', 'FRAUD_CHECK_PENDING', 'CREDIT_PENDING', 'COMPLETED']),
    ).toEqual(['done', 'done', 'done']);
  });

  it('fails the debit step when funds could not be reserved', () => {
    expect(states('FAILED', ['STARTED', 'DEBIT_PENDING', 'DEBIT_FAILED', 'FAILED'])).toEqual([
      'failed',
      'pending',
      'pending',
    ]);
  });

  it('adds a compensation step after a fraud rejection', () => {
    expect(
      states('COMPENSATED', ['STARTED', 'DEBIT_PENDING', 'FRAUD_CHECK_PENDING', 'FRAUD_REJECTED', 'COMPENSATING', 'COMPENSATED']),
    ).toEqual(['done', 'failed', 'pending', 'done']);
  });

  it('fails the compensation step when the refund itself fails', () => {
    const steps = buildSteps('FAILED', new Set<SagaStatus>(['DEBIT_PENDING', 'FRAUD_CHECK_PENDING', 'CREDIT_PENDING', 'CREDIT_FAILED', 'COMPENSATING', 'FAILED']));

    expect(steps.map((s) => s.state)).toEqual(['done', 'done', 'failed', 'failed']);
    expect(steps[3].title).toBe('Compensation');
  });
});
