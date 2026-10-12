import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import Keycloak from 'keycloak-js';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;

  function setUp(keycloak: Partial<Keycloak> | null): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        ...(keycloak ? [{ provide: Keycloak, useValue: keycloak }] : []),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  }

  function fakeKeycloak(overrides: Partial<Keycloak> = {}): Partial<Keycloak> {
    return {
      authenticated: true,
      token: 'access-token',
      updateToken: jasmine.createSpy('updateToken').and.resolveTo(false),
      login: jasmine.createSpy('login').and.resolveTo(),
      ...overrides,
    };
  }

  afterEach(() => backend.verify());

  it('adds the bearer token to API calls', fakeAsync(() => {
    setUp(fakeKeycloak());

    http.get('/api/accounts').subscribe();
    flushMicrotasks();

    const req = backend.expectOne('/api/accounts');
    expect(req.request.headers.get('Authorization')).toBe('Bearer access-token');
    req.flush([]);
  }));

  it('refreshes the token before using it', fakeAsync(() => {
    const keycloak = fakeKeycloak();
    keycloak.updateToken = jasmine.createSpy('updateToken').and.callFake(async () => {
      keycloak.token = 'refreshed-token';
      return true;
    });
    setUp(keycloak);

    http.post('/api/transfers', {}).subscribe();
    flushMicrotasks();

    expect(keycloak.updateToken).toHaveBeenCalledWith(30);
    expect(backend.expectOne('/api/transfers').request.headers.get('Authorization')).toBe('Bearer refreshed-token');
  }));

  it('never sends the token to other hosts', fakeAsync(() => {
    const keycloak = fakeKeycloak();
    setUp(keycloak);

    http.get('https://example.com/api/accounts').subscribe();
    flushMicrotasks();

    expect(backend.expectOne('https://example.com/api/accounts').request.headers.has('Authorization')).toBeFalse();
    expect(keycloak.updateToken).not.toHaveBeenCalled();
  }));

  it('leaves requests untouched when authentication is disabled', fakeAsync(() => {
    setUp(null);

    http.get('/api/accounts').subscribe();
    flushMicrotasks();

    expect(backend.expectOne('/api/accounts').request.headers.has('Authorization')).toBeFalse();
  }));

  it('redirects to login when the session cannot be refreshed', fakeAsync(() => {
    const keycloak = fakeKeycloak({
      updateToken: jasmine.createSpy('updateToken').and.rejectWith(new Error('expired')),
    });
    setUp(keycloak);
    let failed = false;

    http.get('/api/accounts').subscribe({ error: () => (failed = true) });
    flushMicrotasks();

    backend.expectNone('/api/accounts');
    expect(keycloak.login).toHaveBeenCalled();
    expect(failed).toBeTrue();
  }));
});
