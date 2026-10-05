import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  Account,
  AccountStatus,
  CreateAccountRequest,
  FraudEvaluation,
  FraudRules,
  Notification,
  Transfer,
  TransferRequest,
} from './models';

/** Thin typed client over the api-gateway routes. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiUrl;

  // Accounts
  listAccounts(): Observable<Account[]> {
    return this.http.get<Account[]>(`${this.base}/api/accounts`);
  }

  getAccount(id: string): Observable<Account> {
    return this.http.get<Account>(`${this.base}/api/accounts/${id}`);
  }

  createAccount(request: CreateAccountRequest): Observable<Account> {
    return this.http.post<Account>(`${this.base}/api/accounts`, request);
  }

  deposit(id: string, amount: number): Observable<Account> {
    return this.http.post<Account>(`${this.base}/api/accounts/${id}/deposit`, { amount });
  }

  updateAccountStatus(id: string, status: AccountStatus): Observable<Account> {
    return this.http.patch<Account>(`${this.base}/api/accounts/${id}/status`, { status });
  }

  // Transfers
  listTransfers(accountId?: string): Observable<Transfer[]> {
    const params = accountId ? new HttpParams().set('accountId', accountId) : undefined;
    return this.http.get<Transfer[]>(`${this.base}/api/transfers`, { params });
  }

  getTransfer(sagaId: string): Observable<Transfer> {
    return this.http.get<Transfer>(`${this.base}/api/transfers/${sagaId}`);
  }

  createTransfer(request: TransferRequest): Observable<Transfer> {
    return this.http.post<Transfer>(`${this.base}/api/transfers`, request);
  }

  // Notifications
  listNotifications(accountId: string): Observable<Notification[]> {
    return this.http.get<Notification[]>(`${this.base}/api/notifications/${accountId}`);
  }

  // Fraud
  getFraudRules(): Observable<FraudRules> {
    return this.http.get<FraudRules>(`${this.base}/api/fraud/rules`);
  }

  listFraudEvaluations(limit = 100): Observable<FraudEvaluation[]> {
    return this.http.get<FraudEvaluation[]>(`${this.base}/api/fraud/evaluations`, {
      params: new HttpParams().set('limit', limit),
    });
  }

  listBlacklist(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/api/fraud/blacklist`);
  }

  addToBlacklist(accountId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/api/fraud/blacklist/${accountId}`, null);
  }

  removeFromBlacklist(accountId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/fraud/blacklist/${accountId}`);
  }
}
