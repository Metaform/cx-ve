import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { CatalogDataspace, MemberIdFormat, RunSnapshot, RunSummary, VpStatus } from '../../core/models';
import { Dashboard } from './dashboard';

const SELECTION_KEY = 'verification-ui.selection';

const MEMBER_ID: MemberIdFormat = { label: 'Member ID', pattern: 'EX-[0-9]{8}', example: 'EX-00000001' };
const DECADE_X_ID: MemberIdFormat = {
  label: 'DECADE-X-ID', pattern: 'DX-[0-9]{8}', example: 'DX-00000001', assigned: true
};

/**
 * A made-up dataspace whose members declare their ids, and DECADE-X: CCM verifiable where the
 * dataspace is, Traceability and Substance tracing nowhere. DECADE-X assigns its member ids, unless
 * `decadeXAssigns` says otherwise.
 */
function catalog({ example = true, decadeX = false, decadeXAssigns = true } = {}): CatalogDataspace[] {
  return [
    {
      id: 'example-x', displayName: 'Example-X', available: example, memberId: MEMBER_ID,
      useCases: [
        { id: 'ccm', displayName: 'Company Certificate Management', available: example },
        { id: 'traceability', displayName: 'Traceability', available: false }
      ]
    },
    {
      id: 'decade-x', displayName: 'DECADE-X', available: decadeX,
      memberId: { ...DECADE_X_ID, assigned: decadeXAssigns },
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
      memberId: dataspace === 'example-x' ? 'EX-00000099' : 'DX-99999999',
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

      expect(dashboard.dataspaceId).toBe('example-x');
      expect(dashboard.useCaseId).toBe('ccm');
      expect(choice('dataspace', 'Example-X').querySelector('input')!.checked).toBeTrue();
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
      const [example, decadeX] = catalog({ decadeX: true });
      const bothVerifiable = decadeX.useCases.map(useCase => ({ ...useCase, available: true }));
      api.catalog.and.returnValue(of([example, { ...decadeX, useCases: bothVerifiable }]));
      create();
      dashboard.selectDataspace('example-x');
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

      expect(dashboard.dataspaceId).toBe('example-x');
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

      expect(() => dashboard.selectDataspace('example-x')).not.toThrow();
      expect(dashboard.dataspaceId).toBe('example-x');
      expect(dashboard.useCaseId).toBe('ccm');
      finish();
    }));

    it('ignores a remembered selection it cannot read', fakeAsync(() => {
      localStorage.setItem(SELECTION_KEY, '{not json');
      create();

      expect(dashboard.dataspaceId).toBe('example-x');
      finish();
    }));

    it('keeps the selection while a refresh finds it unavailable, blocking the run meanwhile', fakeAsync(() => {
      create();
      dashboard.memberId = 'EX-00000001';
      expect(dashboard.canStart).toBeTrue();

      api.catalog.and.returnValue(of(catalog({ example: false })));
      tick(10000);
      fixture.detectChanges();

      expect(dashboard.dataspaceId).toBe('example-x');
      expect(dashboard.useCaseId).toBe('ccm');
      expect(dashboard.canStart).toBeFalse();
      expect(text('.blocked')).toBe('Example-X is not available in this environment right now.');
      expect(text('.vp-card')).toContain('Example-X is not available in this environment');
      api.vpStatus.calls.reset();
      tick(10000);
      expect(api.vpStatus).not.toHaveBeenCalled();

      api.catalog.and.returnValue(of(catalog()));
      tick(10000);
      fixture.detectChanges();

      expect(dashboard.canStart).toBeTrue();
      expect(api.vpStatus).toHaveBeenCalledWith('example-x');
      finish();
    }));
  });

  describe('verification participant', () => {

    it('is the one of the selected dataspace', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();

      dashboard.selectDataspace('example-x');
      settle();
      expect(api.vpStatus).toHaveBeenCalledWith('example-x');
      expect(kv()['Member ID']).toBe('EX-00000099');

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

      expect(api.ensureVp).toHaveBeenCalledOnceWith('example-x');
      expect(kv()['Member ID']).toBe('EX-00000099');
      finish();
    }));

    it('does not show an ensure that outlives a switch of dataspace on the other card', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      api.vpStatus.and.callFake(dataspace => of(vpStatus(dataspace, false)));
      const ensured = new Subject<VpStatus>();
      api.ensureVp.and.returnValue(ensured);
      create();
      dashboard.selectDataspace('example-x');
      settle();

      dashboard.ensure();
      expect(dashboard.ensuring).toBeTrue();
      dashboard.selectDataspace('decade-x');
      settle();
      expect(dashboard.ensuring).toBeFalse();

      ensured.next(vpStatus('example-x'));
      ensured.complete();

      expect(dashboard.vp?.dataspace).toBe('decade-x');
      expect(dashboard.vp?.exists).toBeFalse();
      finish();
    }));
  });

  describe('member id', () => {

    it("is labelled, exemplified and validated in the selected dataspace's format", fakeAsync(() => {
      create();
      dashboard.memberId = 'EX-123';
      fixture.detectChanges();

      expect(text('label.field:has(input[name="memberId"]) .label')).toBe('Member IDrequired');
      expect(element<HTMLInputElement>('input[name="memberId"]')!.placeholder).toBe('EX-00000001');
      expect(dashboard.memberIdInvalid).toBeTrue();
      expect(dashboard.canStart).toBeFalse();
      expect(dashboard.memberIdHint).toBe('Not a valid Member ID — expected e.g. EX-00000001.');
      expect(text('.blocked')).toBe('Enter a valid Member ID to start the run.');

      dashboard.memberId = ' EX-00000001 ';
      fixture.detectChanges();

      expect(dashboard.memberIdInvalid).toBeFalse();
      expect(dashboard.canStart).toBeTrue();
      expect(element('.blocked')).toBeNull();
      finish();
    }));

    it('is required', fakeAsync(() => {
      create();

      expect(dashboard.startBlocker).toBe('Enter the Member ID to start the run.');
      finish();
    }));

    it('is asked in the format of the selected dataspace', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true, decadeXAssigns: false })));
      create();
      dashboard.selectDataspace('decade-x');
      dashboard.shortName = 'acme';
      settle();

      expect(dashboard.effectiveMemberId).toBe('');
      expect(text('label.field:has(input[name="memberId"]) .label')).toBe('DECADE-X-IDrequired');
      expect(element<HTMLInputElement>('input[name="memberId"]')!.placeholder).toBe('DX-00000001');

      dashboard.memberId = 'DX-00000001';
      expect(dashboard.canStart).toBeTrue();
      finish();
    }));

    it('is not asked in DECADE-X, which assigns it on onboarding', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();
      dashboard.selectDataspace('decade-x');
      settle();

      expect(dashboard.memberIdAssigned).toBeTrue();
      expect(element('input[name="memberId"]')).toBeNull();
      expect((fixture.nativeElement.textContent as string).replace(/\s+/g, ' '))
        .toContain('DECADE-X assigns the DECADE-X-ID when the participant is onboarded');
      expect(dashboard.startBlocker).toBeNull();
      expect(dashboard.canStart).toBeTrue();
      finish();
    }));

    it('is not asked of an external participant in DECADE-X either', fakeAsync(() => {
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      create();
      dashboard.selectDataspace('decade-x');
      dashboard.did = 'did:web:sut.example.com';
      settle();

      expect(element('input[name="memberId"]')).toBeNull();
      expect(dashboard.startBlocker).toBeNull();
      finish();
    }));

    it('is still asked for of an external participant where members declare it', fakeAsync(() => {
      create();
      dashboard.did = 'did:web:sut.example.com';
      fixture.detectChanges();

      expect(dashboard.startBlocker).toBe('Enter the Member ID to start the run.');
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
      dashboard.memberId = 'EX-00000004';

      dashboard.startRun();

      expect(api.startRun).toHaveBeenCalledOnceWith({
        dataspace: 'example-x', useCase: 'ccm', memberId: 'EX-00000004', name: 'ACME', shortName: 'acme'
      });
      expect(navigate).toHaveBeenCalledOnceWith(['/runs', 'r1']);
      finish();
    }));

    it('submits only the DID and member id of an external participant', fakeAsync(() => {
      spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
      api.startRun.and.returnValue(of({ id: 'r1' } as RunSnapshot));
      create();
      dashboard.name = 'typed before the DID';
      dashboard.memberId = 'EX-00000001';
      dashboard.did = ' did:web:sut.example.com ';

      dashboard.startRun();

      expect(api.startRun).toHaveBeenCalledOnceWith({
        dataspace: 'example-x', useCase: 'ccm', memberId: 'EX-00000001', did: 'did:web:sut.example.com'
      });
      finish();
    }));

    it('submits no member id in DECADE-X, which assigns it', fakeAsync(() => {
      spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
      api.catalog.and.returnValue(of(catalog({ decadeX: true })));
      api.startRun.and.returnValue(of({ id: 'r1' } as RunSnapshot));
      create();
      dashboard.selectDataspace('decade-x');
      dashboard.memberId = 'typed in Example-X';
      dashboard.shortName = 'acme';

      dashboard.startRun();

      expect(api.startRun).toHaveBeenCalledOnceWith({ dataspace: 'decade-x', useCase: 'ccm', shortName: 'acme' });
      finish();
    }));

    it('does not submit an invalid member id', fakeAsync(() => {
      create();
      dashboard.memberId = 'EX-123';

      dashboard.startRun();

      expect(api.startRun).not.toHaveBeenCalled();
      finish();
    }));

    it("shows the backend's reason for refusing the run", fakeAsync(() => {
      const reason = "'EX-00000009' is not a member of Example-X";
      api.startRun.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400, error: reason })));
      create();
      dashboard.shortName = 'acme';
      dashboard.memberId = 'EX-00000009';

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
        summary('r1', 'example-x', 'ccm', 'EX-00000004'),
        summary('r2', 'gaia-x', 'pcf', 'GX-1')
      ]));
      create();

      const rows = Array.from(fixture.nativeElement.querySelectorAll('.run-row .sub') as NodeListOf<HTMLElement>)
        .map(row => row.textContent!.replace(/\s+/g, ' ').trim());
      expect(rows).toEqual([
        'EX-00000004 · Example-X · Company Certificate Management',
        'GX-1 · gaia-x · pcf'
      ]);
      finish();
    }));
  });
});
