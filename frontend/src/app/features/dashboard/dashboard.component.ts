import { CurrencyPipe, DatePipe, DecimalPipe, formatDate, formatNumber } from '@angular/common';
import { Component, computed, inject, LOCALE_ID, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { Account, FraudEvaluation, isTerminal, Transfer } from '../../core/models';
import { dailyVolume, outcomeBreakdown } from '../../core/stats';
import { ToastService } from '../../core/toast.service';
import { Bar, BarChartComponent } from '../../shared/bar-chart.component';
import { ShortIdPipe } from '../../shared/short-id.pipe';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, CurrencyPipe, DatePipe, DecimalPipe, ShortIdPipe, StatusBadgeComponent, BarChartComponent],
  template: `
    <div class="page-header">
      <div>
        <h1>Tableau de bord</h1>
        <p class="subtitle">Vue d'ensemble des comptes, virements et décisions anti-fraude.</p>
      </div>
      <button type="button" class="btn btn-secondary" (click)="load()" [disabled]="loading()">Actualiser</button>
    </div>

    <section class="kpis">
      <div class="kpi">
        <span class="kpi-label">Comptes</span>
        <span class="kpi-value">{{ accounts().length }}</span>
        <span class="kpi-hint">{{ activeAccounts() }} actifs</span>
      </div>
      @for (total of totalsByCurrency(); track total.currency) {
        <div class="kpi">
          <span class="kpi-label">Encours {{ total.currency }}</span>
          <span class="kpi-value">{{ total.amount | currency: total.currency : 'symbol' : '1.0-0' }}</span>
          <span class="kpi-hint">somme des soldes</span>
        </div>
      }
      <div class="kpi">
        <span class="kpi-label">Virements</span>
        <span class="kpi-value">{{ transfers().length }}</span>
        <span class="kpi-hint">{{ successRate() | number: '1.0-0' }} % réussis</span>
      </div>
      @if (isAdmin) {
        <div class="kpi kpi-alert">
          <span class="kpi-label">Rejets fraude</span>
          <span class="kpi-value">{{ rejectedCount() }}</span>
          <span class="kpi-hint">sur {{ evaluations().length }} analyses</span>
        </div>
      }
    </section>

    <div class="grid-2">
      <section class="card">
        <div class="card-header">
          <h2>Virements par jour</h2>
          <span class="muted">7 derniers jours</span>
        </div>
        <app-bar-chart [bars]="dailyBars()" unit="virement(s)" />
      </section>

      <section class="card">
        <div class="card-header">
          <h2>Issue des virements</h2>
          <span class="muted">{{ transfers().length }} au total</span>
        </div>
        @if (transfers().length === 0) {
          <p class="empty">Aucun virement pour l'instant.</p>
        } @else {
          <div class="outcome-bar" role="img" [attr.aria-label]="outcomeSummary()">
            @for (o of outcomes(); track o.outcome) {
              @if (o.count > 0) {
                <span [class]="'tone-' + o.tone" [style.flex-grow]="o.count" [title]="o.label + ' : ' + o.count"></span>
              }
            }
          </div>
          <ul class="outcome-legend">
            @for (o of outcomes(); track o.outcome) {
              <li>
                <span [class]="'swatch tone-' + o.tone"></span>
                <span>{{ o.label }}</span>
                <strong>{{ o.count }}</strong>
                <span class="muted">{{ o.percent | number: '1.0-0' }} %</span>
              </li>
            }
          </ul>
        }
      </section>
    </div>

    <div class="grid-2">
      <section class="card">
        <div class="card-header">
          <h2>Derniers virements</h2>
          <a routerLink="/transfers" class="link">Tout voir</a>
        </div>
        @if (recentTransfers().length === 0) {
          <p class="empty">Aucun virement pour l'instant.</p>
        } @else {
          <table class="table">
            <thead>
              <tr><th>Réf.</th><th class="num">Montant</th><th>Statut</th><th>Date</th></tr>
            </thead>
            <tbody>
              @for (t of recentTransfers(); track t.sagaId) {
                <tr>
                  <td><a [routerLink]="['/transfers', t.sagaId]" class="mono">{{ t.sagaId | shortId }}</a></td>
                  <td class="num">{{ t.amount | number: '1.2-2' }}</td>
                  <td><app-status-badge [status]="t.status" /></td>
                  <td>{{ t.createdAt | date: 'short' }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>

      @if (isAdmin) {
      <section class="card">
        <div class="card-header">
          <h2>Alertes fraude récentes</h2>
          <a routerLink="/fraud" class="link">Anti-fraude</a>
        </div>
        @if (recentAlerts().length === 0) {
          <p class="empty">Aucune transaction rejetée.</p>
        } @else {
          <ul class="alert-list">
            @for (e of recentAlerts(); track e.sagaId) {
              <li>
                <div>
                  <a [routerLink]="['/transfers', e.sagaId]" class="mono">{{ e.sagaId | shortId }}</a>
                  · {{ e.amount | number: '1.2-2' }}
                  <div class="rules">
                    @for (rule of e.triggeredRules; track rule) {
                      <span class="chip">{{ rule }}</span>
                    }
                  </div>
                </div>
                <span class="score">{{ e.riskScore }}</span>
              </li>
            }
          </ul>
        }
      </section>
      }
    </div>
  `,
})
export class DashboardComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly locale = inject(LOCALE_ID);
  readonly isAdmin = inject(AuthService).isAdmin();

  readonly loading = signal(false);
  readonly accounts = signal<Account[]>([]);
  readonly transfers = signal<Transfer[]>([]);
  readonly evaluations = signal<FraudEvaluation[]>([]);

  readonly activeAccounts = computed(() => this.accounts().filter((a) => a.status === 'ACTIVE').length);
  readonly totalsByCurrency = computed(() => {
    const totals = new Map<string, number>();
    for (const account of this.accounts()) {
      totals.set(account.currency, (totals.get(account.currency) ?? 0) + account.balance);
    }
    return [...totals.entries()].map(([currency, amount]) => ({ currency, amount }));
  });
  readonly successRate = computed(() => {
    const finished = this.transfers().filter((t) => isTerminal(t.status));
    if (finished.length === 0) return 0;
    return (finished.filter((t) => t.status === 'COMPLETED').length / finished.length) * 100;
  });
  readonly rejectedCount = computed(() => this.evaluations().filter((e) => !e.approved).length);
  readonly recentTransfers = computed(() => this.transfers().slice(0, 6));
  readonly recentAlerts = computed(() => this.evaluations().filter((e) => !e.approved).slice(0, 6));
  readonly dailyBars = computed<Bar[]>(() =>
    dailyVolume(this.transfers()).map((d) => ({
      label: formatDate(d.day, 'EEE d', this.locale),
      value: d.count,
      detail: `${formatNumber(d.amount, this.locale, '1.0-2')} au total`,
    })),
  );
  readonly outcomes = computed(() => outcomeBreakdown(this.transfers()));
  readonly outcomeSummary = computed(() =>
    this.outcomes()
      .map((o) => `${o.label} : ${o.count}`)
      .join(', '),
  );

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    let failed = false;
    const fallback = <T>(value: T) => {
      failed = true;
      return of(value);
    };
    forkJoin({
      accounts: this.api.listAccounts().pipe(catchError(() => fallback<Account[]>([]))),
      transfers: this.api.listTransfers().pipe(catchError(() => fallback<Transfer[]>([]))),
      // Fraud decisions are ADMIN-only on the gateway; other users simply don't see that part.
      evaluations: this.isAdmin
        ? this.api.listFraudEvaluations(200).pipe(catchError(() => fallback<FraudEvaluation[]>([])))
        : of<FraudEvaluation[]>([]),
    }).subscribe(({ accounts, transfers, evaluations }) => {
      this.accounts.set(accounts);
      this.transfers.set(transfers);
      this.evaluations.set(evaluations);
      this.loading.set(false);
      if (failed) {
        this.toast.info('Certaines données sont indisponibles : un ou plusieurs services ne répondent pas.');
      }
    });
  }
}
