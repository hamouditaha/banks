import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  template: `
    <section class="not-found">
      <div class="code">404</div>
      <h1>Page introuvable</h1>
      <p>L'adresse demandée ne correspond à aucune page de l'application.</p>
      <a routerLink="/dashboard" class="btn btn-primary">Retour au tableau de bord</a>
    </section>
  `,
})
export class NotFoundComponent {}
