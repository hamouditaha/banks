import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
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
        @if (auth.isAdmin()) {
          <a routerLink="/fraud" routerLinkActive="active">Anti-fraude</a>
        }
      </nav>
      @if (auth.enabled) {
        <div class="user-menu">
          <span class="user-name">{{ auth.username }}</span>
          <button type="button" class="btn btn-secondary btn-small" (click)="auth.logout()">Déconnexion</button>
        </div>
      }
    </header>
    <main class="container">
      <router-outlet />
    </main>
    <app-toast-container />
  `,
})
export class AppComponent {
  readonly auth = inject(AuthService);
}
