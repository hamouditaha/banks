import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Account, AccountStatus, Notification, Transfer } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { ShortIdPipe } from '../../shared/short-id.pipe';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

@Component({
  selector: 'app-account-detail',
  imports: [ReactiveFormsModule, RouterLink, CurrencyPipe, DatePipe, DecimalPipe, ShortIdPipe, StatusBadgeComponent],
  template: `
    <a routerLink="/accounts" class="back">← Comptes</a>

    @if (account(); as a) {
      <div class="page-header">
        <div>
          <h1>{{ a.ownerName }} <app-status-badge [status]="a.status" /></h1>
          <p class="subtitle mono">{{ a.id }}</p>
        </div>
        <div class="actions">
          <a class="btn btn-primary" [routerLink]="['/transfers']" [queryParams]="{ from: a.id }"
             [class.disabled]="a.status !== 'ACTIVE'">Nouveau virement</a>
        </div>
      </div>

      <div class="grid-sidebar">
        <div class="stack">
          <section class="card balance-card">
            <span class="kpi-label">Solde disponible</span>
            <span class="balance">{{ a.balance | currency: a.currency }}</span>
            <span class="kpi-hint">Ouvert le {{ a.createdAt | date: 'longDate' }}</span>
          </section>

          <section class="card">
            <h2>Dépôt</h2>
            <form [formGroup]="depositForm" (ngSubmit)="deposit()" class="form form-inline">
              <input type="number" formControlName="amount" min="0.01" step="0.01" placeholder="Montant" />
              <button class="btn btn-primary" [disabled]="depositForm.invalid || busy() || a.status !== 'ACTIVE'">Déposer</button>
            </form>
          </section>

          <section class="card">
            <h2>Administration</h2>
            <div class="button-col">
              @if (a.status === 'ACTIVE') {
                <button class="btn btn-secondary" (click)="setStatus('BLOCKED')" [disabled]="busy()">Bloquer le compte</button>
              }
              @if (a.status === 'BLOCKED') {
                <button class="btn btn-secondary" (click)="setStatus('ACTIVE')" [disabled]="busy()">Débloquer le compte</button>
              }
              @if (a.status !== 'CLOSED') {
                <button class="btn btn-danger" (click)="close()" [disabled]="busy()">Clôturer le compte</button>
              }
              <button class="btn btn-secondary" (click)="toggleBlacklist()" [disabled]="busy()">
                {{ blacklisted() ? 'Retirer de la liste noire' : 'Ajouter à la liste noire' }}
              </button>
            </div>
            @if (blacklisted()) {
              <p class="warning-note">Ce compte est sur liste noire : tout virement l'impliquant sera rejeté puis remboursé.</p>
            }
          </section>
        </div>

        <div class="stack">
          <section class="card">
            <div class="card-header">
              <h2>Virements</h2>
              <span class="muted">{{ transfers().length }}</span>
            </div>
            @if (transfers().length === 0) {
              <p class="empty">Aucun virement.</p>
            } @else {
              <div class="table-wrap">
                <table class="table">
                  <thead>
                    <tr><th>Réf.</th><th>Sens</th><th>Contrepartie</th><th class="num">Montant</th><th>Statut</th><th>Date</th></tr>
                  </thead>
                  <tbody>
                    @for (t of transfers(); track t.sagaId) {
                      @let outgoing = t.fromAccountId === a.id;
                      <tr class="clickable" [routerLink]="['/transfers', t.sagaId]">
                        <td class="mono">{{ t.sagaId | shortId }}</td>
                        <td>{{ outgoing ? 'Émis' : 'Reçu' }}</td>
                        <td>{{ ownerOf(outgoing ? t.toAccountId : t.fromAccountId) }}</td>
                        <td class="num" [class.negative]="outgoing" [class.positive]="!outgoing">
                          {{ outgoing ? '−' : '+' }}{{ t.amount | number: '1.2-2' }}
                        </td>
                        <td><app-status-badge [status]="t.status" /></td>
                        <td>{{ t.createdAt | date: 'short' }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </section>

          <section class="card">
            <div class="card-header">
              <h2>Notifications</h2>
              <span class="muted">{{ notifications().length }}</span>
            </div>
            @if (notifications().length === 0) {
              <p class="empty">Aucune notification.</p>
            } @else {
              <ul class="notifications">
                @for (n of notifications(); track n.id) {
                  <li>
                    <app-status-badge [status]="n.status" />
                    <span class="message">{{ n.message }}</span>
                    <span class="muted">{{ n.occurredAt | date: 'short' }}</span>
                  </li>
                }
              </ul>
            }
          </section>
        </div>
      </div>
    } @else if (notFound()) {
      <p class="empty">Ce compte n'existe pas.</p>
    } @else {
      <p class="empty">Chargement…</p>
    }
  `,
})
export class AccountDetailComponent {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  /** Bound from the :id route parameter. */
  readonly id = input.required<string>();

  readonly account = signal<Account | null>(null);
  readonly notFound = signal(false);
  readonly busy = signal(false);
  readonly transfers = signal<Transfer[]>([]);
  readonly notifications = signal<Notification[]>([]);
  readonly blacklist = signal<string[]>([]);
  readonly owners = signal<Map<string, string>>(new Map());
  readonly blacklisted = computed(() => this.blacklist().includes(this.id()));

  readonly depositForm = inject(FormBuilder).nonNullable.group({
    amount: [100, [Validators.required, Validators.min(0.01)]],
  });

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
  }

  ownerOf(accountId: string): string {
    return this.owners().get(accountId) ?? accountId.split('-')[0];
  }

  load(id: string): void {
    this.api.getAccount(id).subscribe({
      next: (account) => this.account.set(account),
      error: (err) => {
        if (err?.status === 404) {
          this.notFound.set(true);
        } else {
          this.toast.error(err, 'Impossible de charger le compte');
        }
      },
    });
    forkJoin({
      transfers: this.api.listTransfers(id).pipe(catchError(() => of([] as Transfer[]))),
      notifications: this.api.listNotifications(id).pipe(catchError(() => of([] as Notification[]))),
      blacklist: this.api.listBlacklist().pipe(catchError(() => of([] as string[]))),
      accounts: this.api.listAccounts().pipe(catchError(() => of([] as Account[]))),
    }).subscribe(({ transfers, notifications, blacklist, accounts }) => {
      this.transfers.set(transfers);
      this.notifications.set(notifications);
      this.blacklist.set(blacklist);
      this.owners.set(new Map(accounts.map((a) => [a.id, a.ownerName])));
    });
  }

  deposit(): void {
    const { amount } = this.depositForm.getRawValue();
    this.busy.set(true);
    this.api.deposit(this.id(), amount).subscribe({
      next: (account) => {
        this.account.set(account);
        this.busy.set(false);
        this.toast.success('Dépôt effectué.');
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(err, 'Dépôt impossible');
      },
    });
  }

  setStatus(status: AccountStatus): void {
    this.busy.set(true);
    this.api.updateAccountStatus(this.id(), status).subscribe({
      next: (account) => {
        this.account.set(account);
        this.busy.set(false);
        this.toast.success(status === 'ACTIVE' ? 'Compte débloqué.' : status === 'BLOCKED' ? 'Compte bloqué.' : 'Compte clôturé.');
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(err, 'Mise à jour du statut impossible');
      },
    });
  }

  close(): void {
    if (confirm('Clôturer définitivement ce compte ? Cette action est irréversible.')) {
      this.setStatus('CLOSED');
    }
  }

  toggleBlacklist(): void {
    const id = this.id();
    const wasBlacklisted = this.blacklisted();
    const request = wasBlacklisted ? this.api.removeFromBlacklist(id) : this.api.addToBlacklist(id);
    this.busy.set(true);
    request.subscribe({
      next: () => {
        this.blacklist.update((list) => (wasBlacklisted ? list.filter((x) => x !== id) : [...list, id]));
        this.busy.set(false);
        this.toast.success(wasBlacklisted ? 'Compte retiré de la liste noire.' : 'Compte ajouté à la liste noire.');
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(err, 'Opération sur la liste noire impossible');
      },
    });
  }
}
