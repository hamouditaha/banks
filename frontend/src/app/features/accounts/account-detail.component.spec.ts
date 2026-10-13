import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import Keycloak from 'keycloak-js';
import { anAccount } from '../../core/testing';
import { AccountDetailComponent } from './account-detail.component';

describe('AccountDetailComponent', () => {
  let backend: HttpTestingController;

  function render(realmRoles: string[]) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Keycloak, useValue: { hasRealmRole: (role: string) => realmRoles.includes(role) } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(AccountDetailComponent);
    fixture.componentRef.setInput('id', 'acc-a');
    fixture.detectChanges();

    backend.expectOne('/api/accounts/acc-a').flush(anAccount());
    backend.expectOne('/api/transfers?accountId=acc-a').flush([]);
    backend.expectOne('/api/notifications/acc-a').flush([]);
    backend.expectOne('/api/accounts').flush([anAccount()]);
    if (realmRoles.includes('ADMIN')) {
      backend.expectOne('/api/fraud/blacklist').flush([]);
    }
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => backend.verify());

  it('shows the back-office actions to admins', () => {
    const el = render(['ADMIN']);

    expect(el.textContent).toContain('Administration');
    expect(el.textContent).toContain('Bloquer le compte');
    expect(el.textContent).toContain('Ajouter à la liste noire');
  });

  it('hides them from clients and never calls the ADMIN-only fraud API', () => {
    const el = render(['CLIENT']);

    expect(el.textContent).toContain('Alice');
    expect(el.textContent).not.toContain('Administration');
    expect(el.textContent).not.toContain('Bloquer le compte');
    backend.expectNone((req) => req.url.startsWith('/api/fraud'));
  });
});
