import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of, Subscription, switchMap, takeWhile, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Account, FraudEvaluation, isTerminal, parseHistory, SagaStatus, Transfer } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { StatusBadgeComponent, statusLabel } from '../../shared/status-badge.component';

type StepState = 'pending' | 'current' | 'done' | 'failed';

interface Step {
  title: string;
  description: string;
  state: StepState;
}

@Component({
  selector: 'app-transfer-detail',
  imports: [RouterLink, CurrencyPipe, DatePipe, DecimalPipe, StatusBadgeComponent],
  template: `
    <a routerLink="/transfers" class="back">← Virements</a>

    @if (transfer(); as t) {
      <div class="page-header">
        <div>
          <h1>Virement de {{ t.amount | number: '1.2-2' }} <app-status-badge [status]="t.status" /></h1>
          <p class="subtitle mono">Saga {{ t.sagaId }}</p>
        </div>
        @if (!finished()) {
          <span class="live"><span class="dot"></span> Suivi en direct</span>
        }
      </div>

      <section class="card">
        <ol class="stepper">
          @for (step of steps(); track step.title) {
            <li [class]="'step step-' + step.state">
              <span class="step-icon">
                @switch (step.state) {
                  @case ('done') { ✓ }
                  @case ('failed') { ✕ }
                  @case ('current') { <span class="spinner"></span> }
                  @default { · }
                }
              </span>
              <div>
                <strong>{{ step.title }}</strong>
                <p>{{ step.description }}</p>
              </div>
            </li>
          }
        </ol>
        @if (t.reason) {
          <p class="error-note">{{ t.reason }}</p>
        }
      </section>

      <div class="grid-2">
        <section class="card">
          <h2>Parties</h2>
          <dl class="details">
            <dt>Émetteur</dt>
            <dd>
              <a [routerLink]="['/accounts', t.fromAccountId]">{{ accountLabel(t.fromAccountId) }}</a>
            </dd>
            <dt>Bénéficiaire</dt>
            <dd>
              <a [routerLink]="['/accounts', t.toAccountId]">{{ accountLabel(t.toAccountId) }}</a>
            </dd>
            <dt>Montant</dt>
            <dd>{{ t.amount | currency: currency() }}</dd>
            <dt>Transaction</dt>
            <dd class="mono">{{ t.transactionId }}</dd>
            <dt>Créé le</dt>
            <dd>{{ t.createdAt | date: 'medium' }}</dd>
          </dl>
          @if (evaluation(); as e) {
            <h3>Décision anti-fraude</h3>
            <div class="risk">
              <div class="risk-bar"><span [style.width.%]="e.riskScore" [class.high]="!e.approved"></span></div>
              <strong>{{ e.riskScore }}/100</strong>
              <span>{{ e.approved ? 'Approuvé' : 'Rejeté' }}</span>
            </div>
            <div class="rules">
              @for (rule of e.triggeredRules; track rule) {
                <span class="chip">{{ rule }}</span>
              } @empty {
                <span class="muted">Aucune règle déclenchée.</span>
              }
            </div>
          }
        </section>

        <section class="card">
          <h2>Journal de la saga</h2>
          <ul class="timeline">
            @for (entry of history(); track $index) {
              <li>
                <span class="muted">{{ entry.at | date: 'HH:mm:ss.SSS' }}</span>
                <strong>{{ label(entry.status) }}</strong>
                @if (entry.note) {
                  <span class="note">{{ entry.note }}</span>
                }
              </li>
            }
          </ul>
        </section>
      </div>
    } @else if (notFound()) {
      <p class="empty">Ce virement n'existe pas.</p>
    } @else {
      <p class="empty">Chargement…</p>
    }
  `,
})
export class TransferDetailComponent {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  private polling?: Subscription;

  /** Bound from the :id route parameter (the saga id). */
  readonly id = input.required<string>();

  readonly transfer = signal<Transfer | null>(null);
  readonly notFound = signal(false);
  readonly accounts = signal<Map<string, Account>>(new Map());
  readonly evaluation = signal<FraudEvaluation | null>(null);

  readonly history = computed(() => parseHistory(this.transfer()?.history ?? []));
  readonly finished = computed(() => {
    const t = this.transfer();
    return t !== null && isTerminal(t.status);
  });
  readonly currency = computed(() => {
    const t = this.transfer();
    return (t && this.accounts().get(t.fromAccountId)?.currency) || 'MAD';
  });
  readonly steps = computed(() => {
    const t = this.transfer();
    return t ? buildSteps(t.status, new Set(this.history().map((h) => h.status))) : [];
  });

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.start(id));
    });
    this.api
      .listAccounts()
      .pipe(catchError(() => of([] as Account[])), takeUntilDestroyed())
      .subscribe((list) => this.accounts.set(new Map(list.map((a) => [a.id, a]))));
  }

  label(status: SagaStatus): string {
    return statusLabel(status);
  }

  accountLabel(accountId: string): string {
    const account = this.accounts().get(accountId);
    return account ? `${account.ownerName} (${accountId.split('-')[0]})` : accountId;
  }

  private start(id: string): void {
    this.polling?.unsubscribe();
    this.transfer.set(null);
    this.notFound.set(false);
    this.evaluation.set(null);

    this.polling = timer(0, 800)
      .pipe(
        switchMap(() => this.api.getTransfer(id)),
        takeWhile((t) => !isTerminal(t.status), true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (t) => {
          this.transfer.set(t);
          if (isTerminal(t.status)) {
            this.loadEvaluation(id);
          }
        },
        error: (err) => {
          if (err?.status === 404) {
            this.notFound.set(true);
          } else {
            this.toast.error(err, 'Impossible de suivre le virement');
          }
        },
      });
  }

  private loadEvaluation(sagaId: string): void {
    this.api
      .listFraudEvaluations(500)
      .pipe(catchError(() => of([] as FraudEvaluation[])))
      .subscribe((list) => this.evaluation.set(list.find((e) => e.sagaId === sagaId) ?? null));
  }
}

/** Maps the saga's current status and visited statuses onto the visual stepper. */
function buildSteps(status: SagaStatus, seen: Set<SagaStatus>): Step[] {
  const state = (current: SagaStatus, done: boolean, failed: boolean): StepState =>
    failed ? 'failed' : done ? 'done' : status === current ? 'current' : 'pending';

  const debitFailed = status === 'FAILED' && !seen.has('FRAUD_CHECK_PENDING');
  const fraudRejected = seen.has('FRAUD_REJECTED');
  const creditFailed = seen.has('CREDIT_FAILED');

  const steps: Step[] = [
    {
      title: 'Débit du compte émetteur',
      description: 'Réservation atomique des fonds (script Lua Redis + verrou distribué).',
      state: state('DEBIT_PENDING', seen.has('FRAUD_CHECK_PENDING'), debitFailed),
    },
    {
      title: 'Analyse anti-fraude',
      description: 'Liste noire, seuils de montant et vélocité sur fenêtre glissante.',
      state: state('FRAUD_CHECK_PENDING', seen.has('CREDIT_PENDING'), fraudRejected),
    },
    {
      title: 'Crédit du bénéficiaire',
      description: 'Versement des fonds sur le compte destinataire.',
      state: state('CREDIT_PENDING', seen.has('COMPLETED'), creditFailed),
    },
  ];

  if (seen.has('COMPENSATING')) {
    steps.push({
      title: 'Compensation',
      description: 'Remboursement automatique du compte émetteur.',
      state: state('COMPENSATING', status === 'COMPENSATED', status === 'FAILED'),
    });
  }
  return steps;
}
