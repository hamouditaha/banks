import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { interval } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Account, isTerminal, SagaStatus, Transfer } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { ShortIdPipe } from '../../shared/short-id.pipe';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

type Filter = 'ALL' | 'IN_PROGRESS' | SagaStatus;

function differentAccounts(group: AbstractControl): ValidationErrors | null {
  const { fromAccountId, toAccountId } = group.value;
  return fromAccountId && fromAccountId === toAccountId ? { sameAccount: true } : null;
}

@Component({
  selector: 'app-transfer-list',
  imports: [ReactiveFormsModule, RouterLink, CurrencyPipe, DatePipe, DecimalPipe, ShortIdPipe, StatusBadgeComponent],
  template: `
    <div class="page-header">
      <div>
        <h1>Virements</h1>
        <p class="subtitle">Chaque virement suit une saga : débit → analyse anti-fraude → crédit, avec remboursement automatique en cas d'échec.</p>
      </div>
    </div>

    <div class="grid-sidebar">
      <section class="card">
        <h2>Nouveau virement</h2>
        <form [formGroup]="form" (ngSubmit)="submit()" class="form">
          <label>
            Compte débité
            <select formControlName="fromAccountId">
              <option value="" disabled>Choisir…</option>
              @for (a of activeAccounts(); track a.id) {
                <option [value]="a.id">{{ a.ownerName }} — {{ a.balance | currency: a.currency }}</option>
              }
            </select>
          </label>
          <label>
            Compte crédité
            <select formControlName="toAccountId">
              <option value="" disabled>Choisir…</option>
              @for (a of accounts(); track a.id) {
                <option [value]="a.id" [disabled]="a.id === form.controls.fromAccountId.value">
                  {{ a.ownerName }} ({{ a.id.split('-')[0] }})
                </option>
              }
            </select>
          </label>
          <label>
            Montant
            <input type="number" formControlName="amount" min="0.01" step="0.01" />
          </label>
          @if (form.hasError('sameAccount')) {
            <p class="error-note">Les deux comptes doivent être différents.</p>
          }
          @if (selectedSource(); as src) {
            @if (form.controls.amount.value > src.balance) {
              <p class="warning-note">Montant supérieur au solde : le débit sera refusé.</p>
            }
          }
          <button type="submit" class="btn btn-primary" [disabled]="form.invalid || sending()">
            {{ sending() ? 'Envoi…' : 'Envoyer' }}
          </button>
        </form>
      </section>

      <section class="card">
        <div class="card-header">
          <h2>Historique</h2>
          <div class="segmented">
            @for (f of filters; track f.value) {
              <button type="button" [class.active]="filter() === f.value" (click)="filter.set(f.value)">{{ f.label }}</button>
            }
          </div>
        </div>
        @if (filtered().length === 0) {
          <p class="empty">Aucun virement.</p>
        } @else {
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr><th>Réf.</th><th>De</th><th>Vers</th><th class="num">Montant</th><th>Statut</th><th>Date</th></tr>
              </thead>
              <tbody>
                @for (t of filtered(); track t.sagaId) {
                  <tr class="clickable" [routerLink]="['/transfers', t.sagaId]">
                    <td class="mono">{{ t.sagaId | shortId }}</td>
                    <td>{{ ownerOf(t.fromAccountId) }}</td>
                    <td>{{ ownerOf(t.toAccountId) }}</td>
                    <td class="num">{{ t.amount | number: '1.2-2' }}</td>
                    <td><app-status-badge [status]="t.status" /></td>
                    <td>{{ t.createdAt | date: 'short' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>
  `,
})
export class TransferListComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  /** Optional `?from=<accountId>` query parameter to preselect the source account. */
  readonly from = input<string>();

  readonly accounts = signal<Account[]>([]);
  readonly transfers = signal<Transfer[]>([]);
  readonly sending = signal(false);
  readonly filter = signal<Filter>('ALL');
  readonly filters: { value: Filter; label: string }[] = [
    { value: 'ALL', label: 'Tous' },
    { value: 'IN_PROGRESS', label: 'En cours' },
    { value: 'COMPLETED', label: 'Terminés' },
    { value: 'COMPENSATED', label: 'Remboursés' },
    { value: 'FAILED', label: 'Échoués' },
  ];

  readonly form = inject(FormBuilder).nonNullable.group(
    {
      fromAccountId: ['', Validators.required],
      toAccountId: ['', Validators.required],
      amount: [100, [Validators.required, Validators.min(0.01)]],
    },
    { validators: differentAccounts },
  );

  private readonly sourceId = signal('');
  readonly activeAccounts = computed(() => this.accounts().filter((a) => a.status === 'ACTIVE'));
  readonly selectedSource = computed(() => this.accounts().find((a) => a.id === this.sourceId()) ?? null);
  readonly owners = computed(() => new Map(this.accounts().map((a) => [a.id, a.ownerName])));
  readonly filtered = computed(() => {
    const f = this.filter();
    if (f === 'ALL') return this.transfers();
    if (f === 'IN_PROGRESS') return this.transfers().filter((t) => !isTerminal(t.status));
    return this.transfers().filter((t) => t.status === f);
  });

  ngOnInit(): void {
    this.form.controls.fromAccountId.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((id) => this.sourceId.set(id));
    const from = this.from();
    if (from) {
      this.form.controls.fromAccountId.setValue(from);
    }

    this.api.listAccounts().subscribe({
      next: (accounts) => this.accounts.set(accounts),
      error: (err) => this.toast.error(err, 'Impossible de charger les comptes'),
    });
    this.refreshTransfers();

    // Keep in-flight sagas moving on screen without a manual refresh.
    interval(2000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.transfers().some((t) => !isTerminal(t.status))) {
          this.refreshTransfers();
        }
      });
  }

  ownerOf(accountId: string): string {
    return this.owners().get(accountId) ?? accountId.split('-')[0];
  }

  submit(): void {
    if (this.form.invalid) return;
    this.sending.set(true);
    this.api.createTransfer(this.form.getRawValue()).subscribe({
      next: (transfer) => {
        this.sending.set(false);
        this.toast.info('Virement initié, suivi en temps réel…');
        this.router.navigate(['/transfers', transfer.sagaId]);
      },
      error: (err) => {
        this.sending.set(false);
        this.toast.error(err, 'Virement impossible');
      },
    });
  }

  private refreshTransfers(): void {
    this.api.listTransfers().subscribe({
      next: (transfers) => this.transfers.set(transfers),
      error: (err) => this.toast.error(err, 'Impossible de charger les virements'),
    });
  }
}
