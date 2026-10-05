import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Account, FraudEvaluation, FraudRules } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { ShortIdPipe } from '../../shared/short-id.pipe';

@Component({
  selector: 'app-fraud',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, ShortIdPipe],
  template: `
    <div class="page-header">
      <div>
        <h1>Anti-fraude</h1>
        <p class="subtitle">Moteur à règles : un score de risque est calculé pour chaque virement avant le crédit.</p>
      </div>
      <button type="button" class="btn btn-secondary" (click)="load()">Actualiser</button>
    </div>

    <div class="grid-sidebar">
      <div class="stack">
        <section class="card">
          <h2>Règles actives</h2>
          @if (rules(); as r) {
            <ul class="rule-list">
              <li><span>Liste noire (émetteur ou bénéficiaire)</span><strong>rejet immédiat</strong></li>
              <li><span>Montant ≥ {{ r.largeAmountThreshold | number }}</span><strong>+40</strong></li>
              <li><span>Montant ≥ {{ r.veryLargeAmountThreshold | number }}</span><strong>+70</strong></li>
              <li><span>&gt; {{ r.velocityMaxTransactions }} virements en {{ r.velocityWindowSeconds }} s</span><strong>+50</strong></li>
              <li class="threshold"><span>Seuil de rejet</span><strong>score ≥ {{ r.rejectScoreThreshold }}</strong></li>
            </ul>
          } @else {
            <p class="empty">Service anti-fraude indisponible.</p>
          }
        </section>

        <section class="card">
          <h2>Liste noire</h2>
          <form class="form form-inline" (ngSubmit)="addToBlacklist()">
            <select name="candidate" [(ngModel)]="candidate">
              <option value="" disabled>Choisir un compte…</option>
              @for (a of candidates(); track a.id) {
                <option [value]="a.id">{{ a.ownerName }} ({{ a.id | shortId }})</option>
              }
            </select>
            <button class="btn btn-danger" [disabled]="!candidate">Ajouter</button>
          </form>
          @if (blacklist().length === 0) {
            <p class="empty">Aucun compte sur liste noire.</p>
          } @else {
            <ul class="blacklist">
              @for (id of blacklist(); track id) {
                <li>
                  <a [routerLink]="['/accounts', id]">{{ ownerOf(id) }}</a>
                  <span class="mono muted">{{ id | shortId }}</span>
                  <button type="button" class="btn btn-small btn-secondary" (click)="removeFromBlacklist(id)">Retirer</button>
                </li>
              }
            </ul>
          }
        </section>
      </div>

      <section class="card">
        <div class="card-header">
          <h2>Décisions récentes</h2>
          <div class="segmented">
            <button type="button" [class.active]="onlyRejected() === false" (click)="onlyRejected.set(false)">Toutes</button>
            <button type="button" [class.active]="onlyRejected()" (click)="onlyRejected.set(true)">Rejetées ({{ rejectedCount() }})</button>
          </div>
        </div>
        @if (visibleEvaluations().length === 0) {
          <p class="empty">Aucune décision enregistrée.</p>
        } @else {
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr><th>Saga</th><th>De → Vers</th><th class="num">Montant</th><th>Score</th><th>Règles</th><th>Date</th></tr>
              </thead>
              <tbody>
                @for (e of visibleEvaluations(); track e.sagaId) {
                  <tr>
                    <td><a class="mono" [routerLink]="['/transfers', e.sagaId]">{{ e.sagaId | shortId }}</a></td>
                    <td>{{ ownerOf(e.fromAccountId) }} → {{ ownerOf(e.toAccountId) }}</td>
                    <td class="num">{{ e.amount | number: '1.2-2' }}</td>
                    <td><span class="score" [class.score-ok]="e.approved">{{ e.riskScore }}</span></td>
                    <td>
                      <div class="rules">
                        @for (rule of e.triggeredRules; track rule) {
                          <span class="chip">{{ rule }}</span>
                        }
                      </div>
                    </td>
                    <td>{{ e.evaluatedAt | date: 'short' }}</td>
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
export class FraudComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  readonly rules = signal<FraudRules | null>(null);
  readonly blacklist = signal<string[]>([]);
  readonly evaluations = signal<FraudEvaluation[]>([]);
  readonly accounts = signal<Account[]>([]);
  readonly onlyRejected = signal(false);
  candidate = '';

  readonly owners = computed(() => new Map(this.accounts().map((a) => [a.id, a.ownerName])));
  readonly candidates = computed(() => this.accounts().filter((a) => !this.blacklist().includes(a.id)));
  readonly rejectedCount = computed(() => this.evaluations().filter((e) => !e.approved).length);
  readonly visibleEvaluations = computed(() =>
    this.onlyRejected() ? this.evaluations().filter((e) => !e.approved) : this.evaluations(),
  );

  ngOnInit(): void {
    this.load();
  }

  ownerOf(accountId: string): string {
    return this.owners().get(accountId) ?? accountId.split('-')[0];
  }

  load(): void {
    forkJoin({
      rules: this.api.getFraudRules().pipe(catchError(() => of(null))),
      blacklist: this.api.listBlacklist().pipe(catchError(() => of([] as string[]))),
      evaluations: this.api.listFraudEvaluations(200).pipe(catchError(() => of([] as FraudEvaluation[]))),
      accounts: this.api.listAccounts().pipe(catchError(() => of([] as Account[]))),
    }).subscribe(({ rules, blacklist, evaluations, accounts }) => {
      this.rules.set(rules);
      this.blacklist.set(blacklist);
      this.evaluations.set(evaluations);
      this.accounts.set(accounts);
    });
  }

  addToBlacklist(): void {
    const id = this.candidate;
    if (!id) return;
    this.api.addToBlacklist(id).subscribe({
      next: () => {
        this.blacklist.update((list) => [...list, id]);
        this.candidate = '';
        this.toast.success(`${this.ownerOf(id)} ajouté à la liste noire.`);
      },
      error: (err) => this.toast.error(err, 'Ajout à la liste noire impossible'),
    });
  }

  removeFromBlacklist(id: string): void {
    this.api.removeFromBlacklist(id).subscribe({
      next: () => {
        this.blacklist.update((list) => list.filter((x) => x !== id));
        this.toast.success(`${this.ownerOf(id)} retiré de la liste noire.`);
      },
      error: (err) => this.toast.error(err, 'Retrait de la liste noire impossible'),
    });
  }
}
