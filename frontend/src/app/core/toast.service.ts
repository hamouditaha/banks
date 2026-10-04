import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';

export interface Toast {
  id: number;
  kind: 'success' | 'error' | 'info';
  text: string;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  private nextId = 1;
  readonly toasts = signal<Toast[]>([]);

  success(text: string): void {
    this.push('success', text);
  }

  info(text: string): void {
    this.push('info', text);
  }

  error(error: unknown, fallback = 'Une erreur est survenue'): void {
    this.push('error', describeError(error, fallback));
  }

  dismiss(id: number): void {
    this.toasts.update((list) => list.filter((t) => t.id !== id));
  }

  private push(kind: Toast['kind'], text: string): void {
    const id = this.nextId++;
    this.toasts.update((list) => [...list, { id, kind, text }]);
    setTimeout(() => this.dismiss(id), 5000);
  }
}

/** Extracts the backend's `{ error: "..." }` message, or a readable fallback. */
export function describeError(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'Impossible de joindre la passerelle API (port 8080).';
    }
    const body = error.error;
    if (body && typeof body === 'object' && typeof body.error === 'string') {
      return body.error;
    }
    if (error.status === 503) {
      return 'Service indisponible : vérifiez que tous les microservices sont démarrés.';
    }
    return `${fallback} (HTTP ${error.status})`;
  }
  return fallback;
}
