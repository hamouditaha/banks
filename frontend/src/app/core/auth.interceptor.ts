import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import Keycloak from 'keycloak-js';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { environment } from '../../environments/environment';

/** Refresh the access token when it expires within this many seconds. */
const MIN_TOKEN_VALIDITY_SECONDS = 30;

/**
 * Adds the Keycloak access token to calls to our own API (never to third-party URLs),
 * refreshing it first if it is about to expire. A failed refresh sends the user back to login.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const keycloak = inject(Keycloak, { optional: true });
  if (!keycloak?.authenticated || !isApiRequest(req.url)) {
    return next(req);
  }
  return from(keycloak.updateToken(MIN_TOKEN_VALIDITY_SECONDS)).pipe(
    catchError((error) => {
      void keycloak.login();
      return throwError(() => error);
    }),
    switchMap(() => next(req.clone({ setHeaders: { Authorization: `Bearer ${keycloak.token}` } }))),
  );
};

function isApiRequest(url: string): boolean {
  return url.startsWith(`${environment.apiUrl}/api/`);
}
