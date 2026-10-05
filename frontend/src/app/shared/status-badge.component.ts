import { Component, computed, input } from '@angular/core';
import { AccountStatus, SagaStatus } from '../core/models';

const LABELS: Record<string, string> = {
  ACTIVE: 'Actif',
  BLOCKED: 'Bloqué',
  CLOSED: 'Clôturé',
  STARTED: 'Démarré',
  DEBIT_PENDING: 'Débit en cours',
  DEBIT_FAILED: 'Débit refusé',
  FRAUD_CHECK_PENDING: 'Analyse fraude',
  FRAUD_REJECTED: 'Rejeté (fraude)',
  CREDIT_PENDING: 'Crédit en cours',
  CREDIT_FAILED: 'Crédit échoué',
  COMPLETED: 'Terminé',
  COMPENSATING: 'Compensation',
  COMPENSATED: 'Remboursé',
  FAILED: 'Échoué',
};

const TONES: Record<string, string> = {
  ACTIVE: 'success',
  COMPLETED: 'success',
  BLOCKED: 'warning',
  COMPENSATING: 'warning',
  COMPENSATED: 'warning',
  FRAUD_REJECTED: 'danger',
  DEBIT_FAILED: 'danger',
  CREDIT_FAILED: 'danger',
  FAILED: 'danger',
  CLOSED: 'muted',
};

@Component({
  selector: 'app-status-badge',
  template: `<span class="badge" [class]="'badge badge-' + tone()">{{ label() }}</span>`,
})
export class StatusBadgeComponent {
  readonly status = input.required<AccountStatus | SagaStatus>();
  readonly label = computed(() => LABELS[this.status()] ?? this.status());
  readonly tone = computed(() => TONES[this.status()] ?? 'info');
}

export function statusLabel(status: string): string {
  return LABELS[status] ?? status;
}
