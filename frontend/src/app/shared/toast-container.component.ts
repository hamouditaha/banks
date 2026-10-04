import { Component, inject } from '@angular/core';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-toast-container',
  template: `
    <div class="toasts" aria-live="polite">
      @for (toast of toastService.toasts(); track toast.id) {
        <div class="toast toast-{{ toast.kind }}" role="status">
          <span>{{ toast.text }}</span>
          <button type="button" class="icon-btn" (click)="toastService.dismiss(toast.id)" aria-label="Fermer">×</button>
        </div>
      }
    </div>
  `,
})
export class ToastContainerComponent {
  readonly toastService = inject(ToastService);
}
