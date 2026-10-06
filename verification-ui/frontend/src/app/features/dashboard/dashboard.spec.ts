import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { CatalogDataspace, MemberIdFormat, RunSnapshot, RunSummary, VpStatus } from '../../core/models';
import { Dashboard } from './dashboard';

const SELECTION_KEY = 'verification-ui.selection';

const BPN: MemberIdFormat = { label: 'BPN', pattern: 'BPNL[0-9A-Z]{12}', example: 'BPNL000000000001' };
const DECADE_X_ID: MemberIdFormat = { label: 'DECADE-X-ID', pattern: 'DX-[0-9]{8}', example: 'DX-00000001' };

/** Catena-X and DECADE-X as configured: CCM verifiable where the dataspace is, Traceability and Substance tracing nowhere. */
function catalog({ catenaX = true, decadeX = false } = {}): CatalogDataspace[] {
  return [
    {
      id: 'catena-x', displayName: 'Catena-X', available: catenaX, memberId: BPN,
      useCases: [
        { id: 'ccm', displayName: 'Company Certificate Management', available: catenaX },
        { id: 'traceability', displayName: 'Traceability', available: false }
      ]
    },
    {
      id: 'decade-x', displayName: 'DECADE-X', available: decadeX,
      memberId: DECADE_X_ID,
      useCases: [
        { id: 'ccm', displayName: 'Company Certificate Management', available: decadeX },
        { id: 'substance-tracing', displayName: 'Substance tracing', available: false }
      ]
    }
  ];
}

function vpStatus(dataspace: string, exists = true): VpStatus {
  return {
    dataspace,
    exists,
    offerSeeded: exists,
    membership: exists ? {
      externalId: `vp-${dataspace}`,
      dataspace,
      name: 'Verification Participant',
      did: `did:web:vp-${dataspace}`,
      memberId: dataspace === 'catena-x' ? 'BPNLVERIFY000001' : 'DX-99999999',
      state: 'CREDENTIALS_OFFERED',
      onboardingProcessId: null,
      tenantId: null,
      participantProfileId: null,
      participantContextId: null,
      failureReason: null
    } : null
  };
}

function summary(id: string, dataspace: string, useCase: string, memberId: string): RunSummary {
  return {
    id, dataspace, useCase, memberId,
    state: 'RUNNING', currentStep: null, startedAt: '2026-10-01T10:00:00Z', finishedAt: null,
    name: `Participant ${id}`, shortName: `put-${id}`, externalId: null, externallyHosted: false
  };
}

describe('Dashboard', () => {

  let api: jasmine.SpyObj<ApiService>;
  let fixture: ComponentFixture<Dashboard>;
  let dashboard: Dashboard;

  beforeEach(() => {
    localStorage.removeItem(SELECTION_KEY);
    api = jasmine.createSpyObj<ApiService>('ApiService', ['catalog', 'vpStatus', 'ensureVp', 'startRun', 'runs']);
    api.catalog.and.returnValue(of(catalog()));
    api.vpStatus.and.callFake(dataspace => of(vpStatus(dataspace)));
    api.runs.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [Dashboard],
      providers: [provideRouter([]), { provide: ApiService, useValue: api }]
    });
  });

  afterEach(() => localStorage.removeItem(SELECTION_KEY));

  /** Creates the dashboard and lets its first polls land — inside fakeAsync only. */
  function create(): void {
    fixture = TestBed.createComponent(Dashboard);
    dashboard = fixture.componentInstance;
    fixture.detectChanges();
    settle();
  }

  function settle(): void {
    tick();
    fixture.detectChanges();
  }

  /** Stops the dashboard's polls, which fakeAsync would otherwise report as left running. */
  function finish(): void {
    fixture.destroy();
  }

  function element<T extends HTMLElement = HTMLElement>(selector: string): T | null {
    return fixture.nativeElement.querySelector(selector) as T | null;
  }

  function text(selector: string): string {
    return element(selector)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  /** The verification participant card's identity rows, label → value. */
  function kv(): Record<string, string> {
    const rows: Record<string, string> = {};
    (fixture.nativeElement.querySelectorAll('.vp-card .kv dt') as NodeListOf<HTMLElement>).forEach(dt => {
      rows[dt.textContent!.trim()] = dt.nextElementSibling!.textContent!.trim();
    });
    return rows;
  }

  function choice(group: 'dataspace' | 'useCase', name: string): HTMLLabelElement {
    const inputs = Array.from(fixture.nativeElement.querySelectorAll(`input[name="${group}"]`)) as HTMLInputElement[];
    const label = inputs.map(input => input.closest('label')!).find(card => card.textContent!.includes(name));
    if (!label) {
      throw new Error(`no ${group} choice "${name}"`);
    }
    return label;
  }

  describe('selection', () => {

    it('selects the only available dataspace and use case on its own', fakeAsync(() => {
      create();

      expect(dashboard.dataspaceId).toBe('catena-x');
      expect(dashboard.useCaseId).toBe('ccm');
      expect(choice('dataspace', 'Catena-X').querySelector('input')!.checked).toBeTrue();
      expect(element('form')).not.toBeNull();
      finish();
    }));

    it('lists what cannot be picked as disabled, saying why', fakeAsync(() => {
      create();

      const decadeX = choice('dataspace', 'DECADE-X');
      expect(decadeX.querySelector('input')!.disabled).toBeTrue();
      expect(decadeX.textContent).toContain('not available in this environment');
      const traceability = choice('useCase', 'Traceability');
      expect(traceability.querySelector('input')!.disabled).toBeTrue();
      expect(traceability.textContent).toContain('not verifiable yet');
      finish();
    }));

    it('leaves a real choice to the user, offering the run form only once it is made', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();

      expect(dashboard.dataspaceId).toBeNull();
      expect(element('form')).toBeNull();
      expect(text('.vp-card')).toContain('pick one to see it');
      expect(api.vpStatus).not.toHaveBeenCalled();

      choice('dataspace', 'DECADE-X').querySelector('input')!.click();
      settle();

      expect(dashboard.dataspaceId).toBe('decade-x');
      expect(dashboard.useCaseId).toBe('ccm');
      expect(element('form')).not.toBeNull();
      finish();
    }));

    it('keeps the use case when switching to a dataspace that has it too', fakeAsync(() => {
      const [catenaX, decadeX] = catalog({ decadeX: true });
      const bothVerifiable = decadeX.useCases.map(useCase => ({ ...useCase, available: true }));
      api.catalog.and.returnValue(of([catenaX, { ...decadeX, useCases: bothVerifiable }]));
      create();
      dashboard.selectDataspace('catena-x');
      dashboard.selectUseCase('ccm');

      dashboard.selectDataspace('decade-x');

      expect(dashboard.useCaseId).toBe('ccm');
      finish();
    }));

    it('restores the remembered selection', fakeAsync(() => {
      localStorage.setItem(SELECTION_KEY, JSON.stringify({ dataspace: 'decade-x', useCase: 'ccm' }));
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();

      expect(dashboard.dataspaceId).toBe('decade-x');
      expect(dashboard.useCaseId).toBe('ccm');
      finish();
    }));

    it('passes over a remembered selection that cannot be picked', fakeAsync(() => {
      localStorage.setItem(SELECTION_KEY, JSON.stringify({ dataspace: 'decade-x', useCase: 'ccm' }));
      create();

      expect(dashboard.dataspaceId).toBe('catena-x');
      finish();
    }));

    it('remembers what the user picks', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();

      dashboard.selectDataspace('decade-x');

      expect(JSON.parse(localStorage.getItem(SELECTION_KEY)!)).toEqual({ dataspace: 'decade-x', useCase: 'ccm' });
      finish();
    }));

    it('does without browser storage', fakeAsync(() => {
      spyOn(Storage.prototype, 'getItem').and.throwError('storage is blocked');
      spyOn(Storage.prototype, 'setItem').and.throwError('storage is blocked');
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();

      expect(() => dashboard.selectDataspace('catena-x')).not.toThrow();
      expect(dashboard.dataspaceId).toBe('catena-x');
      expect(dashboard.useCaseId).toBe('ccm');
      finish();
    }));

    it('ignores a remembered selection it cannot read', fakeAsync(() => {
      localStorage.setItem(SELECTION_KEY, '{not json');
      create();

      expect(dashboard.dataspaceId).toBe('catena-x');
      finish();
    }));

    it('keeps the selection while a refresh finds it unavailable, blocking the run meanwhile', fakeAsync(() => {
      create();
      dashboard.deriveBpn = false;
      dashboard.memberId = 'BPNL000000000001';
      expect(dashboard.canStart).toBeTrue();

      api.catalog.and.returnValue(of(catalog({ catenaX: false })));
      tick(10000);
      fixture.detectChanges();

      expect(dashboard.dataspaceId).toBe('catena-x');
      expect(dashboard.useCaseId).toBe('ccm');
      expect(dashboard.canStart).toBeFalse();
      expect(text('.blocked')).toBe('Catena-X is not available in this environment right now.');
      expect(text('.vp-card')).toContain('Catena-X is not available in this environment');
      api.vpStatus.calls.reset();
      tick(10000);
      expect(api.vpStatus).not.toHaveBeenCalled();

      api.catalog.and.returnValue(of(catalog()));
      tick(10000);
      fixture.detectChanges();

      expect(dashboard.canStart).toBeTrue();
      expect(api.vpStatus).toHaveBeenCalledWith('catena-x');
      finish();
    }));
  });

  describe('verification participant', () => {

    it('is the one of the selected dataspace', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();

      dashboard.selectDataspace('catena-x');
      settle();
      expect(api.vpStatus).toHaveBeenCalledWith('catena-x');
      expect(kv()['BPN']).toBe('BPNLVERIFY000001');

      dashboard.selectDataspace('decade-x');
      settle();
      expect(api.vpStatus).toHaveBeenCalledWith('decade-x');
      expect(kv()['DECADE-X-ID']).toBe('DX-99999999');
      finish();
    }));

    it('is ensured for the selected dataspace', fakeAsync(() => {
      api.vpStatus.and.callFake(dataspace => of(vpStatus(dataspace, false)));
      api.ensureVp.and.callFake(dataspace => of(vpStatus(dataspace)));
      create();

      element<HTMLButtonElement>('.vp-card .btn.primary')!.click();
      settle();

      expect(api.ensureVp).toHaveBeenCalledOnceWith('catena-x');
      expect(kv()['BPN']).toBe('BPNLVERIFY000001');
      finish();
    }));

    it('does not show an ensure that outlives a switch of dataspace on the other card', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      api.vpStatus.and.callFake(dataspace => of(vpStatus(dataspace, false)));
      const ensured = new Subject<VpStatus>();
      api.ensureVp.and.returnValue(ensured);
      create();
      dashboard.selectDataspace('catena-x');
      settle();

      dashboard.ensure();
      expect(dashboard.ensuring).toBeTrue();
      dashboard.selectDataspace('decade-x');
      settle();
      expect(dashboard.ensuring).toBeFalse();

      ensured.next(vpStatus('catena-x'));
      ensured.complete();

      expect(dashboard.vp?.dataspace).toBe('decade-x');
      expect(dashboard.vp?.exists).toBeFalse();
      finish();
    }));
  });

  describe('member id', () => {

    it("is labelled, exemplified and validated in the selected dataspace's format", fakeAsync(() => {
      create();
      dashboard.deriveBpn = false;
      dashboard.memberId = 'BPNL123';
      fixture.detectChanges();

      expect(text('label.field:has(input[name="memberId"]) .label')).toBe('BPNrequired');
      expect(element<HTMLInputElement>('input[name="memberId"]')!.placeholder).toBe('BPNL000000000001');
      expect(dashboard.memberIdInvalid).toBeTrue();
      expect(dashboard.canStart).toBeFalse();
      expect(dashboard.memberIdHint).toBe('Not a valid BPN — expected e.g. BPNL000000000001.');
      expect(text('.blocked')).toBe('Enter a valid BPN to start the run.');

      dashboard.memberId = ' BPNL000000000001 ';
      fixture.detectChanges();

      expect(dashboard.memberIdInvalid).toBeFalse();
      expect(dashboard.canStart).toBeTrue();
      expect(element('.blocked')).toBeNull();
      finish();
    }));

    it('is required', fakeAsync(() => {
      create();

      expect(dashboard.startBlocker).toBe('Enter a short name to derive the BPN from, or untick to type one.');
      dashboard.deriveBpn = false;
      expect(dashboard.startBlocker).toBe('Enter the BPN to start the run.');
      finish();
    }));

    it('is derived from the short name in Catena-X', fakeAsync(() => {
      create();
      dashboard.shortName = 'acme';
      fixture.detectChanges();

      expect(dashboard.derivingBpn).toBeTrue();
      expect(dashboard.effectiveMemberId).toBe('BPNL002D993A0000');
      expect(text('.derive')).toContain('Derive BPN from short name');
      expect(dashboard.canStart).toBeTrue();
      finish();
    }));

    it('is never derived outside Catena-X', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();
      dashboard.selectDataspace('decade-x');
      dashboard.shortName = 'acme';
      settle();

      expect(dashboard.derivingBpn).toBeFalse();
      expect(dashboard.effectiveMemberId).toBe('');
      expect(element('.derive')).toBeNull();
      expect(text('label.field:has(input[name="memberId"]) .label')).toBe('DECADE-X-IDrequired');
      expect(element<HTMLInputElement>('input[name="memberId"]')!.placeholder).toBe('DX-00000001');

      dashboard.memberId = 'DX-00000001';
      expect(dashboard.canStart).toBeTrue();
      finish();
    }));

    it('is never derived for an external participant', fakeAsync(() => {
      create();
      dashboard.did = 'did:web:sut.example.com';
      fixture.detectChanges();

      expect(dashboard.derivingBpn).toBeFalse();
      expect(element('.derive')).toBeNull();
      finish();
    }));

    it('is asked of an external participant in DECADE-X, which declares it like a BPN', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();
      dashboard.selectDataspace('decade-x');
      dashboard.did = 'did:web:sut.example.com';
      settle();

      expect(dashboard.startBlocker).toBe('Enter the DECADE-X-ID to start the run.');
      finish();
    }));

    it('is still asked for of an external participant in Catena-X', fakeAsync(() => {
      create();
      dashboard.did = 'did:web:sut.example.com';
      fixture.detectChanges();

      expect(dashboard.startBlocker).toBe('Enter the BPN to start the run.');
      finish();
    }));

    it('is not taken over by derivation when coming to Catena-X with one typed', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();
      dashboard.selectDataspace('decade-x');
      dashboard.memberId = 'DX-00000001';

      dashboard.selectDataspace('catena-x');

      expect(dashboard.deriveBpn).toBeFalse();
      expect(dashboard.effectiveMemberId).toBe('DX-00000001');
      expect(dashboard.memberIdInvalid).toBeTrue();
      finish();
    }));
  });

  describe('starting a run', () => {

    it('submits the selected use case and dataspace with the participant', fakeAsync(() => {
      const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
      api.startRun.and.returnValue(of({ id: 'r1' } as RunSnapshot));
      create();
      dashboard.name = 'ACME';
      dashboard.shortName = 'acme';

      dashboard.startRun();

      expect(api.startRun).toHaveBeenCalledOnceWith({
        dataspace: 'catena-x', useCase: 'ccm', memberId: 'BPNL002D993A0000', name: 'ACME', shortName: 'acme'
      });
      expect(navigate).toHaveBeenCalledOnceWith(['/runs', 'r1']);
      finish();
    }));

    it('submits only the DID and member id of an external participant', fakeAsync(() => {
      spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
      api.startRun.and.returnValue(of({ id: 'r1' } as RunSnapshot));
      create();
      dashboard.name = 'typed before the DID';
      dashboard.memberId = 'BPNL000000000001';
      dashboard.did = ' did:web:sut.example.com ';
      dashboard.onDidChange();

      dashboard.startRun();

      expect(api.startRun).toHaveBeenCalledOnceWith({
        dataspace: 'catena-x', useCase: 'ccm', memberId: 'BPNL000000000001', did: 'did:web:sut.example.com'
      });
      finish();
    }));

    it('does not submit an invalid member id', fakeAsync(() => {
      create();
      dashboard.deriveBpn = false;
      dashboard.memberId = 'BPNL123';

      dashboard.startRun();

      expect(api.startRun).not.toHaveBeenCalled();
      finish();
    }));

    it("shows the backend's reason for refusing the run", fakeAsync(() => {
      const reason = "'BPNL00000000000X' is not a valid Catena-X BPN (expected e.g. BPNL000000000001)";
      api.startRun.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400, error: reason })));
      create();
      dashboard.shortName = 'acme';

      dashboard.startRun();
      fixture.detectChanges();

      expect(dashboard.starting).toBeFalse();
      expect(text('.error')).toBe(`Starting the run failed: ${reason}`);
      finish();
    }));
  });

  describe('run list', () => {

    it("names every run's dataspace and use case, falling back to their ids", fakeAsync(() => {
      api.runs.and.returnValue(of([
        summary('r1', 'catena-x', 'ccm', 'BPNL002D993A0000'),
        summary('r2', 'gaia-x', 'pcf', 'GX-1')
      ]));
      create();

      const rows = Array.from(fixture.nativeElement.querySelectorAll('.run-row .sub') as NodeListOf<HTMLElement>)
        .map(row => row.textContent!.replace(/\s+/g, ' ').trim());
      expect(rows).toEqual([
        'BPNL002D993A0000 · Catena-X · Company Certificate Management',
        'GX-1 · gaia-x · pcf'
      ]);
      finish();
    }));
  });
});
