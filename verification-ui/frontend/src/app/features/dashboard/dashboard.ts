import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { BehaviorSubject, EMPTY, Subscription, catchError, exhaustMap, map, of, switchMap, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { CATENA_X, bpnFor } from '../../core/bpn';
import {
  CCM,
  dataspaceDisplayName,
  findDataspace,
  findUseCase,
  isValidMemberId,
  keepOrPick,
  memberIdLabelOf,
  pickAvailable,
  useCaseDisplayName
} from '../../core/catalog';
import {
  CatalogDataspace,
  CatalogUseCase,
  MemberIdFormat,
  RunSummary,
  StartRunRequest,
  VpStatus,
  stepLabel
} from '../../core/models';

/** The last dataspace and use case the user picked, remembered across visits. */
interface RememberedSelection {
  dataspace?: string;
  useCase?: string;
}

const SELECTION_KEY = 'verification-ui.selection';

@Component({
  selector: 'app-dashboard',
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css'
})
export class Dashboard implements OnInit, OnDestroy {

  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly subscriptions: Subscription[] = [];
  /** The dataspace whose verification participant the card shows and polls; null for none. */
  private readonly vpDataspace = new BehaviorSubject<string | null>(null);
  /** Dataspaces with an ensure in flight — it can take minutes, and the user may look elsewhere. */
  private readonly ensuringFor = new Set<string>();
  private remembered = readSelection();

  catalog: CatalogDataspace[] | null = null;
  catalogUnreachable = false;
  dataspaceId: string | null = null;
  useCaseId: string | null = null;

  vp: VpStatus | null = null;
  vpUnreachable = false;
  vpError = '';
  runs: RunSummary[] = [];
  starting = false;
  error = '';

  name = '';
  shortName = '';
  memberId = '';
  did = '';
  deriveBpn = true;

  readonly stepLabel = stepLabel;

  ngOnInit(): void {
    // Refreshed rather than loaded once: availability follows the Membership Hub, which the BFF
    // reports as serving nothing while it cannot reach it — the dashboard must recover from that.
    // A slow load is let finish (exhaustMap), not cancelled by the next tick: nothing on this page
    // works without the catalog.
    this.subscriptions.push(timer(0, 10000)
      .pipe(exhaustMap(() => this.api.catalog().pipe(catchError(() => of(null)))))
      .subscribe(catalog => {
        this.catalogUnreachable = catalog === null;
        if (catalog) {
          this.applyCatalog(catalog);
        }
      }));
    // Each dataspace has its own verification participant; nothing is polled until there is one
    // to show, and switching dataspaces drops the previous one's in-flight poll.
    this.subscriptions.push(this.vpDataspace
      .pipe(switchMap(dataspace => dataspace === null ? EMPTY : timer(0, 5000).pipe(
        switchMap(() => this.api.vpStatus(dataspace).pipe(catchError(() => of(null)))),
        map(status => ({ dataspace, status })))))
      .subscribe(({ dataspace, status }) => {
        // an in-flight ensure holds the truth; don't let a stale poll overwrite its outcome —
        // unless the card has nothing to show yet (the user came back to it mid-ensure)
        if (this.ensuringFor.has(dataspace) && this.vp) {
          return;
        }
        this.vpUnreachable = status === null;
        if (status) {
          this.vp = status;
        }
      }));
    this.subscriptions.push(timer(0, 3000)
      .pipe(switchMap(() => this.api.runs().pipe(catchError(() => of(null)))))
      .subscribe(runs => {
        if (runs) {
          this.runs = runs;
        }
      }));
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach(subscription => subscription.unsubscribe());
  }

  get selectedDataspace(): CatalogDataspace | null {
    return findDataspace(this.catalog ?? [], this.dataspaceId);
  }

  get selectedUseCase(): CatalogUseCase | null {
    return findUseCase(this.selectedDataspace, this.useCaseId);
  }

  /** The user's pick of a dataspace. The use case carries over where the dataspace has it too. */
  selectDataspace(id: string): void {
    const useCases = findDataspace(this.catalog ?? [], id)?.useCases ?? [];
    this.useCaseId = pickAvailable(useCases, this.useCaseId, this.remembered.useCase);
    this.setDataspace(id);
    this.remember();
  }

  selectUseCase(id: string): void {
    this.useCaseId = id;
    this.error = '';
    this.remember();
  }

  get isCcm(): boolean {
    return this.useCaseId === CCM;
  }

  /** A DID means the participant already runs elsewhere — this environment only verifies it. */
  get external(): boolean {
    return this.did.trim().length > 0;
  }

  get memberIdFormat(): MemberIdFormat | null {
    return this.selectedDataspace?.memberId ?? null;
  }

  /** What the selected dataspace calls a member id — "BPN" in Catena-X. */
  get memberIdLabel(): string {
    return memberIdLabelOf(this.selectedDataspace);
  }

  /**
   * Whether deriving the member id from the short name is on offer. The derivation yields a BPN,
   * so only in Catena-X; and only for a participant this environment onboards — an external
   * system's BPN is agreed with its operator and has to be typed, and there is no short name to
   * derive it from anyway.
   */
  get canDeriveBpn(): boolean {
    return this.dataspaceId === CATENA_X && !this.external;
  }

  /**
   * Whether the member id is the dataspace's to assign: for an external participant of a dataspace
   * whose onboarding assigns it (Decade-X). The run then takes none and adopts the assigned one.
   */
  get memberIdAssigned(): boolean {
    return this.external && !!this.memberIdFormat?.assignedToExternal;
  }

  /** Whether the member-id field is standing in for a BPN derived from the short name. */
  get derivingBpn(): boolean {
    return this.canDeriveBpn && this.deriveBpn;
  }

  get derivedBpn(): string {
    return this.shortName.trim() ? bpnFor(this.shortName.trim()) : '';
  }

  /**
   * The member id the run is submitted with. Mandatory in both modes, so the backend is never
   * left to invent one — empty here means the form is incomplete, not that a default applies.
   * The exception is one the dataspace assigns: then there is none to submit.
   */
  get effectiveMemberId(): string {
    if (this.memberIdAssigned) {
      return '';
    }
    return this.derivingBpn ? this.derivedBpn : this.memberId.trim();
  }

  /** There is a member id, but not one in the selected dataspace's format. */
  get memberIdInvalid(): boolean {
    const memberId = this.effectiveMemberId;
    return memberId.length > 0 && !isValidMemberId(this.memberIdFormat, memberId);
  }

  get memberIdPlaceholder(): string {
    if (this.memberIdAssigned) {
      return 'assigned on onboarding';
    }
    if (this.derivingBpn) {
      return this.derivedBpn || 'enter a short name to derive from';
    }
    return this.memberIdFormat?.example || '';
  }

  get memberIdHint(): string {
    const label = this.memberIdLabel;
    if (this.memberIdInvalid) {
      const example = this.memberIdFormat?.example;
      return example ? `Not a valid ${label} — expected e.g. ${example}.` : `Not a valid ${label}.`;
    }
    if (this.memberIdAssigned) {
      return `Assigned by ${this.selectedDataspace?.displayName} when the system is onboarded — nothing to enter.`;
    }
    if (this.external) {
      return `Required — and it must be the ${label} the system under test already runs with: `
        + `the credential issued here is for exactly that ${label}, and the system is checked against it.`;
    }
    return this.derivingBpn
      ? `Required. Derived from the short name — enter one, or untick to type the ${label}.`
      : 'Required.';
  }

  /**
   * Why the run cannot start, in the user's terms — Enter in a text field submits too, and
   * silently doing nothing is worse than a greyed button. Null once it can.
   */
  get startBlocker(): string | null {
    const dataspace = this.selectedDataspace;
    const useCase = this.selectedUseCase;
    if (!dataspace || !useCase) {
      return 'Pick a dataspace and a use case to start a run.';
    }
    if (!dataspace.available) {
      return `${dataspace.displayName} is not available in this environment right now.`;
    }
    if (!useCase.available) {
      return `${useCase.displayName} cannot be verified in ${dataspace.displayName} yet.`;
    }
    if (!this.effectiveMemberId && !this.memberIdAssigned) {
      return this.derivingBpn
        ? `Enter a short name to derive the ${this.memberIdLabel} from, or untick to type one.`
        : `Enter the ${this.memberIdLabel} to start the run.`;
    }
    if (this.memberIdInvalid) {
      return `Enter a valid ${this.memberIdLabel} to start the run.`;
    }
    return null;
  }

  get canStart(): boolean {
    return !this.starting && this.startBlocker === null;
  }

  /** Whether the shown dataspace's verification participant is being ensured right now. */
  get ensuring(): boolean {
    const shown = this.vpDataspace.value;
    return shown !== null && this.ensuringFor.has(shown);
  }

  onDidChange(): void {
    this.keepTypedMemberId();
  }

  ensure(): void {
    const dataspace = this.vpDataspace.value;
    if (dataspace === null || this.ensuringFor.has(dataspace)) {
      return;
    }
    this.ensuringFor.add(dataspace);
    this.vpError = '';
    this.api.ensureVp(dataspace).subscribe({
      next: status => {
        this.ensuringFor.delete(dataspace);
        // the user may have moved on to another dataspace meanwhile — its card is not this one's
        if (dataspace === this.vpDataspace.value) {
          this.vp = status;
          this.vpUnreachable = false;
        }
      },
      error: err => {
        this.ensuringFor.delete(dataspace);
        if (dataspace === this.vpDataspace.value) {
          this.vpError = friendly(err, 'Ensuring the verification participant failed');
        }
      }
    });
  }

  startRun(): void {
    const dataspace = this.selectedDataspace;
    const useCase = this.selectedUseCase;
    if (!this.canStart || !dataspace || !useCase) {
      return;
    }
    this.starting = true;
    this.error = '';
    // The member id travels whenever the form takes one — it is mandatory there, so nothing here
    // relies on the backend deriving one; only one the dataspace assigns is left out. Name and
    // short name stay the backend's to default, and
    // for an external participant they are not this environment's to state at all: it is already
    // named by the system that runs it, so the deactivated fields are not sent even if they hold
    // text typed before the DID was entered.
    const request: StartRunRequest = {
      dataspace: dataspace.id,
      useCase: useCase.id
    };
    if (!this.memberIdAssigned) {
      request.memberId = this.effectiveMemberId;
    }
    if (this.external) {
      request.did = this.did.trim();
    } else {
      if (this.name.trim()) {
        request.name = this.name.trim();
      }
      if (this.shortName.trim()) {
        request.shortName = this.shortName.trim();
      }
    }
    this.api.startRun(request).subscribe({
      next: run => {
        this.starting = false;
        this.router.navigate(['/runs', run.id]);
      },
      error: err => {
        this.starting = false;
        this.error = friendly(err, 'Starting the run failed');
      }
    });
  }

  dataspaceName(id: string): string {
    return dataspaceDisplayName(this.catalog ?? [], id);
  }

  useCaseName(run: RunSummary): string {
    return useCaseDisplayName(this.catalog ?? [], run.dataspace, run.useCase);
  }

  membershipChip(state: string): string {
    if (state === 'PROVISIONED' || state === 'CREDENTIALS_OFFERED') {
      return 'ok';
    }
    if (state === 'REJECTED' || state === 'FAILED') {
      return 'failed';
    }
    return 'running';
  }

  runChip(state: string): string {
    return state === 'SUCCEEDED' ? 'ok' : state === 'FAILED' ? 'failed' : 'running';
  }

  /** Takes in a (re)loaded catalog without moving a selection out from under the user. */
  private applyCatalog(catalog: CatalogDataspace[]): void {
    this.catalog = catalog;
    const dataspaceId = keepOrPick(catalog, this.dataspaceId, this.remembered.dataspace);
    const useCases = findDataspace(catalog, dataspaceId)?.useCases ?? [];
    this.useCaseId = dataspaceId === this.dataspaceId
      ? keepOrPick(useCases, this.useCaseId, this.remembered.useCase)
      : pickAvailable(useCases, this.useCaseId, this.remembered.useCase);
    this.setDataspace(dataspaceId);
  }

  private setDataspace(id: string | null): void {
    if (id !== this.dataspaceId) {
      this.dataspaceId = id;
      this.error = '';
      this.keepTypedMemberId();
    }
    // The card follows the selection, but only into a dataspace the hub serves: one it does not
    // has no participant to show. A change of what the card shows starts it afresh.
    const shown = this.selectedDataspace?.available ? id : null;
    if (shown !== this.vpDataspace.value) {
      this.vp = null;
      this.vpUnreachable = false;
      this.vpError = '';
      this.vpDataspace.next(shown);
    }
  }

  /**
   * Derivation must not silently take over a member id the user typed. Whenever it would switch
   * back on — leaving external mode, or coming (back) to Catena-X — it would disable the field
   * and substitute the derived BPN with no cue beyond the input greying out. A field with
   * something in it keeps derivation off.
   */
  private keepTypedMemberId(): void {
    if (this.canDeriveBpn && this.memberId.trim()) {
      this.deriveBpn = false;
    }
  }

  /**
   * Remembers the selection a pick of the user's left. Only theirs: what a catalog load selects on
   * their behalf (the sole available option) is no choice of theirs, and must not displace one.
   */
  private remember(): void {
    this.remembered = {
      dataspace: this.dataspaceId ?? undefined,
      useCase: this.useCaseId ?? this.remembered.useCase
    };
    writeSelection(this.remembered);
  }
}

/**
 * The remembered selection, or none. Browser storage can be absent, blocked or throwing (private
 * windows, cleared site data); the dashboard then simply starts without one.
 */
function readSelection(): RememberedSelection {
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(SELECTION_KEY) ?? 'null');
    if (stored && typeof stored === 'object') {
      const { dataspace, useCase } = stored as Record<string, unknown>;
      return {
        dataspace: typeof dataspace === 'string' ? dataspace : undefined,
        useCase: typeof useCase === 'string' ? useCase : undefined
      };
    }
  } catch {
    // unreadable or unparseable: nothing remembered
  }
  return {};
}

function writeSelection(selection: RememberedSelection): void {
  try {
    localStorage.setItem(SELECTION_KEY, JSON.stringify(selection));
  } catch {
    // not remembered across visits, which is all the storage was for
  }
}

function friendly(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse) {
    const body = typeof err.error === 'string' ? err.error : err.error?.message ?? err.error?.detail;
    return body ? `${fallback}: ${body}` : `${fallback} (HTTP ${err.status})`;
  }
  return fallback;
}
