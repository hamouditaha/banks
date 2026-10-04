import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Account } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { ShortIdPipe } from '../../shared/short-id.pipe';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

@Component({
  selector: 'app-account-list',
  imports: [ReactiveFormsModule, RouterLink, CurrencyPipe, DatePipe, ShortIdPipe, StatusBadgeComponent],
  template: `
    <div class="page-header">
      <div>
        <h1>Comptes</h1>
        <p class="subtitle">Ouvrez un compte et consultez les soldes en temps réel.</p>
      </div>
    </div>

    <div class="grid-sidebar">
      <section class="card">
        <h2>Ouvrir un compte</h2>
        <form [formGroup]="form" (ngSubmit)="create()" class="form">
          <label>
            Titulaire
            <input formControlName="ownerName" placeholder="ex. Alice Martin" autocomplete="off" />
          </label>
          <label>
            Devise
            <select formControlName="currency">
              <option value="MAD">MAD</option>
              <option value="EUR">EUR</option>
              <option value="USD">USD</option>
            </select>
          </label>
          <label>
            Solde initial
            <input type="number" formControlName="openingBalance" min="0" step="0.01" />
          </label>
          <button type="submit" class="btn btn-primary" [disabled]="form.invalid || saving()">
            {{ saving() ? 'Création…' : 'Créer le compte' }}
          </button>
        </form>
      </section>

      <section class="card">
        <div class="card-header">
          <h2>{{ filtered().length }} compte(s)</h2>
          <input class="search" type="search" placeholder="Rechercher un titulaire ou un ID"
                 [value]="query()" (input)="query.set($any($event.target).value)" />
        </div>
        @if (loading()) {
          <p class="empty">Chargement…</p>
        } @else if (filtered().length === 0) {
          <p class="empty">Aucun compte.</p>
        } @else {
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr><th>Titulaire</th><th>ID</th><th class="num">Solde</th><th>Statut</th><th>Ouvert le</th></tr>
              </thead>
              <tbody>
                @for (a of filtered(); track a.id) {
                  <tr class="clickable" [routerLink]="['/accounts', a.id]">
                    <td><strong>{{ a.ownerName }}</strong></td>
                    <td class="mono">{{ a.id | shortId }}</td>
                    <td class="num">{{ a.balance | currency: a.currency }}</td>
                    <td><app-status-badge [status]="a.status" /></td>
                    <td>{{ a.createdAt | date: 'mediumDate' }}</td>
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
export class AccountListComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  readonly accounts = signal<Account[]>([]);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly query = signal('');
  readonly filtered = computed(() => {
    const q = this.query().trim().toLowerCase();
    if (!q) return this.accounts();
    return this.accounts().filter((a) => a.ownerName.toLowerCase().includes(q) || a.id.includes(q));
  });

  readonly form = inject(FormBuilder).nonNullable.group({
    ownerName: ['', [Validators.required, Validators.maxLength(80)]],
    currency: ['MAD', Validators.required],
    openingBalance: [0, [Validators.required, Validators.min(0)]],
  });

  ngOnInit(): void {
    this.api.listAccounts().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.toast.error(err, 'Impossible de charger les comptes');
      },
    });
  }

  create(): void {
    if (this.form.invalid) return;
    this.saving.set(true);
    this.api.createAccount(this.form.getRawValue()).subscribe({
      next: (account) => {
        this.accounts.update((list) => [account, ...list]);
        this.form.reset({ ownerName: '', currency: this.form.controls.currency.value, openingBalance: 0 });
        this.saving.set(false);
        this.toast.success(`Compte de ${account.ownerName} créé.`);
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(err, 'Création du compte impossible');
      },
    });
  }
}
