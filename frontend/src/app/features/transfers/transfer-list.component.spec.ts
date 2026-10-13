import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { FormControl, FormGroup } from '@angular/forms';
import { provideRouter, Router } from '@angular/router';
import { anAccount, aTransfer } from '../../core/testing';
import { differentAccounts, TransferListComponent, transfersToCsv } from './transfer-list.component';

describe('TransferListComponent', () => {
  describe('helpers', () => {
    it('rejects a transfer to the same account', () => {
      const group = (from: string, to: string) =>
        new FormGroup({ fromAccountId: new FormControl(from), toAccountId: new FormControl(to) });

      expect(differentAccounts(group('a', 'a'))).toEqual({ sameAccount: true });
      expect(differentAccounts(group('a', 'b'))).toBeNull();
      expect(differentAccounts(group('', ''))).toBeNull();
    });

    it('exports transfers as CSV with owner names, French labels and decimal commas', () => {
      const csv = transfersToCsv(
        [aTransfer({ amount: 1234.5, status: 'COMPENSATED', reason: 'fraud rejected; score=100' })],
        new Map([['acc-a', 'Alice'], ['acc-b', 'Bob']]),
      );
      const [header, row] = csv.split('\r\n');

      expect(header.split(';')[0]).toBe('Saga');
      expect(row).toBe('saga-1;Alice;acc-a;Bob;acc-b;1234,50;Remboursé;"fraud rejected; score=100";2026-10-13T10:00:00Z');
    });
  });

  describe('new transfer form', () => {
    let backend: HttpTestingController;

    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
      });
      backend = TestBed.inject(HttpTestingController);
    });

    afterEach(() => backend.verify());

    function create() {
      const fixture = TestBed.createComponent(TransferListComponent);
      fixture.detectChanges();
      backend.expectOne('/api/accounts').flush([
        anAccount({ id: 'acc-a', ownerName: 'Alice' }),
        anAccount({ id: 'acc-b', ownerName: 'Bob' }),
        anAccount({ id: 'acc-c', ownerName: 'Carol', status: 'BLOCKED' }),
      ]);
      backend.expectOne('/api/transfers').flush([]);
      fixture.detectChanges();
      return fixture;
    }

    it('only offers active accounts as the source', () => {
      const fixture = create();

      expect(fixture.componentInstance.activeAccounts().map((a) => a.id)).toEqual(['acc-a', 'acc-b']);
      fixture.destroy();
    });

    it('posts the transfer and opens its live tracking page', () => {
      const fixture = create();
      const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
      const component = fixture.componentInstance;

      component.form.setValue({ fromAccountId: 'acc-a', toAccountId: 'acc-b', amount: 250 });
      component.submit();

      const req = backend.expectOne('/api/transfers');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ fromAccountId: 'acc-a', toAccountId: 'acc-b', amount: 250 });
      req.flush(aTransfer({ sagaId: 'saga-42', status: 'STARTED' }));

      expect(navigate).toHaveBeenCalledWith(['/transfers', 'saga-42']);
      expect(component.sending()).toBeFalse();
      fixture.destroy();
    });

    it('does not submit an invalid form', () => {
      const fixture = create();
      const component = fixture.componentInstance;

      component.form.setValue({ fromAccountId: 'acc-a', toAccountId: 'acc-a', amount: 10 });
      component.submit();

      backend.expectNone('/api/transfers');
      expect(component.form.hasError('sameAccount')).toBeTrue();
      fixture.destroy();
    });
  });
});
