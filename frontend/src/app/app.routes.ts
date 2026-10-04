import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Tableau de bord · Banks',
    loadComponent: () => import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'accounts',
    title: 'Comptes · Banks',
    loadComponent: () => import('./features/accounts/account-list.component').then((m) => m.AccountListComponent),
  },
  {
    path: 'accounts/:id',
    title: 'Compte · Banks',
    loadComponent: () => import('./features/accounts/account-detail.component').then((m) => m.AccountDetailComponent),
  },
  {
    path: 'transfers',
    title: 'Virements · Banks',
    loadComponent: () => import('./features/transfers/transfer-list.component').then((m) => m.TransferListComponent),
  },
  {
    path: 'transfers/:id',
    title: 'Suivi du virement · Banks',
    loadComponent: () => import('./features/transfers/transfer-detail.component').then((m) => m.TransferDetailComponent),
  },
  {
    path: 'fraud',
    title: 'Anti-fraude · Banks',
    loadComponent: () => import('./features/fraud/fraud.component').then((m) => m.FraudComponent),
  },
  { path: '**', redirectTo: 'dashboard' },
];
