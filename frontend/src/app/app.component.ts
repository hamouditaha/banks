import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ToastContainerComponent } from './shared/toast-container.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ToastContainerComponent],
  template: `
    <header class="topbar">
      <a routerLink="/" class="brand">
        <span class="brand-mark">B</span>
        <span>Banks</span>
      </a>
      <nav class="nav">
        <a routerLink="/dashboard" routerLinkActive="active">Tableau de bord</a>
        <a routerLink="/accounts" routerLinkActive="active">Comptes</a>
        <a routerLink="/transfers" routerLinkActive="active">Virements</a>
        <a routerLink="/fraud" routerLinkActive="active">Anti-fraude</a>
      </nav>
    </header>
    <main class="container">
      <router-outlet />
    </main>
    <app-toast-container />
  `,
})
export class AppComponent {}
