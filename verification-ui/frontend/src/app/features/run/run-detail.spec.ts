import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { CatalogDataspace, RunSnapshot } from '../../core/models';
import { RunDetail } from './run-detail';

const CATALOG: CatalogDataspace[] = [
  {
    id: 'catena-x', displayName: 'Catena-X', available: true,
    memberId: { label: 'BPN', pattern: 'BPNL[0-9A-Z]{12}', example: 'BPNL000000000001' },
    useCases: [
      { id: 'ccm', displayName: 'Company Certificate Management', available: true },
      { id: 'parts-tracking', displayName: 'Parts Tracking', available: false }
    ]
  }
];

function snapshot(overrides: Partial<RunSnapshot> = {}): RunSnapshot {
  return {
    id: 'r1',
    dataspace: 'catena-x',
    useCase: 'ccm',
    state: 'SUCCEEDED',
    startedAt: '2026-10-01T10:00:00Z',
    finishedAt: '2026-10-01T10:05:00Z',
    participant: {
      name: 'ACME', shortName: 'acme', memberId: 'BPNL002D993A0000', uniqueId: 'DE002D993A',
      externalId: 'x1', did: 'did:web:acme', participantContextId: 'pc1', onboardingProcessId: 'p1',
      externallyHosted: false
    },
    verificationParticipant: null,
    steps: [],
    checklist: [{ subject: 'events.onboarding.started', minCount: 1, actualCount: 1, satisfied: true }],
    failureReason: null,
    failedStep: null,
    ...overrides
  };
}

describe('RunDetail', () => {

  let api: jasmine.SpyObj<ApiService>;
  let fixture: ComponentFixture<RunDetail>;

  beforeEach(() => {
    api = jasmine.createSpyObj<ApiService>('ApiService', ['catalog', 'run', 'events']);
    api.catalog.and.returnValue(of(CATALOG));
    api.run.and.returnValue(of(snapshot()));
    api.events.and.returnValue(of({ events: [] }));
    TestBed.configureTestingModule({
      imports: [RunDetail],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'r1' }) } } }
      ]
    });
  });

  /** Shows the run once its first poll lands; destroyed before the test ends, which stops it. */
  function render(): void {
    fixture = TestBed.createComponent(RunDetail);
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
  }

  function text(selector: string): string {
    const element = fixture.nativeElement.querySelector(selector) as HTMLElement | null;
    return element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  it('names the dataspace and use case, and labels the member id as the dataspace does', fakeAsync(() => {
    render();

    expect(text('.scope')).toBe('Catena-X · Company Certificate Management');
    expect(text('.idlabel')).toBe('BPN');
    expect(text('.idline')).toContain('BPNL002D993A0000');
    fixture.destroy();
  }));

  it('falls back to the ids, and a generic label, for what the catalog does not know', fakeAsync(() => {
    api.run.and.returnValue(of(snapshot({ dataspace: 'gaia-x', useCase: 'pcf' })));
    render();

    expect(text('.scope')).toBe('gaia-x · pcf');
    expect(text('.idlabel')).toBe('Member ID');
    fixture.destroy();
  }));

  it('still shows the run when the catalog cannot be read', fakeAsync(() => {
    api.catalog.and.returnValue(throwError(() => new Error('unreachable')));
    render();

    expect(text('.scope')).toBe('catena-x · ccm');
    expect(text('.idlabel')).toBe('Member ID');
    fixture.destroy();
  }));

  it('reports a CCM success as the certificate transfer it is', fakeAsync(() => {
    render();

    expect(text('.ok-banner')).toBe(
      'Onboarding and certificate transfer completed — all 1 expected event subjects are in the ledger.');
    fixture.destroy();
  }));

  it('reports the success of any other use case generically', fakeAsync(() => {
    api.run.and.returnValue(of(snapshot({ useCase: 'parts-tracking' })));
    render();

    expect(text('.ok-banner')).toBe('Verification completed — all 1 expected event subjects are in the ledger.');
    fixture.destroy();
  }));
});
