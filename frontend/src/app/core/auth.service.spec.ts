import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import Keycloak from 'keycloak-js';
import { adminGuard } from './admin.guard';
import { AuthService } from './auth.service';

describe('AuthService and adminGuard', () => {
  function setUp(realmRoles: string[] | null): void {
    const keycloak: Partial<Keycloak> | null = realmRoles && {
      tokenParsed: { preferred_username: 'alice' },
      hasRealmRole: (role: string) => realmRoles.includes(role),
      logout: jasmine.createSpy('logout').and.resolveTo(),
    };
    TestBed.configureTestingModule({
      providers: [provideRouter([]), ...(keycloak ? [{ provide: Keycloak, useValue: keycloak }] : [])],
    });
  }

  const runGuard = () => TestBed.runInInjectionContext(() => adminGuard({} as never, {} as never));

  it('exposes the logged-in user and its realm roles', () => {
    setUp(['CLIENT']);
    const auth = TestBed.inject(AuthService);

    expect(auth.enabled).toBeTrue();
    expect(auth.username).toBe('alice');
    expect(auth.hasRole('CLIENT')).toBeTrue();
    expect(auth.isAdmin()).toBeFalse();
  });

  it('lets admins open the fraud console', () => {
    setUp(['ADMIN', 'CLIENT']);

    expect(runGuard()).toBeTrue();
  });

  it('sends other users back to the dashboard', () => {
    setUp(['CLIENT']);

    const result = runGuard();

    expect(result instanceof UrlTree).toBeTrue();
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/dashboard');
  });

  it('opens everything when authentication is disabled (dev build)', () => {
    setUp(null);
    const auth = TestBed.inject(AuthService);

    expect(auth.enabled).toBeFalse();
    expect(auth.username).toBeNull();
    expect(auth.isAdmin()).toBeTrue();
    expect(runGuard()).toBeTrue();
  });
});
