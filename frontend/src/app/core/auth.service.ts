import { Injectable, inject } from '@angular/core';
import Keycloak from 'keycloak-js';

export type Role = 'ADMIN' | 'CLIENT';

/**
 * Current user as seen by the UI. When authentication is disabled (dev build, no Keycloak
 * provider) the user is treated as an admin so every screen of the demo stays reachable.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly keycloak = inject(Keycloak, { optional: true });

  get enabled(): boolean {
    return this.keycloak !== null;
  }

  get username(): string | null {
    return (this.keycloak?.tokenParsed?.['preferred_username'] as string | undefined) ?? null;
  }

  hasRole(role: Role): boolean {
    return this.keycloak ? this.keycloak.hasRealmRole(role) : true;
  }

  isAdmin(): boolean {
    return this.hasRole('ADMIN');
  }

  logout(): void {
    void this.keycloak?.logout({ redirectUri: window.location.origin });
  }
}
