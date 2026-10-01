import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription, catchError, of, switchMap, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { bpnFor } from '../../core/bpn';
import { RunSummary, VpStatus, stepLabel } from '../../core/models';

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

  vp: VpStatus | null = null;
  vpUnreachable = false;
  runs: RunSummary[] = [];
  ensuring = false;
  starting = false;
  error = '';

  name = '';
  shortName = '';
  bpn = '';
  did = '';
  deriveBpn = true;

  readonly stepLabel = stepLabel;

  ngOnInit(): void {
    this.subscriptions.push(timer(0, 5000)
      .pipe(switchMap(() => this.api.vpStatus().pipe(catchError(() => of(null)))))
      .subscribe(status => {
        // an in-flight ensure holds the truth; don't let a stale poll overwrite its outcome
        if (this.ensuring) {
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

  get derivedBpn(): string {
    return this.shortName.trim() ? bpnFor(this.shortName.trim()) : '';
  }

  /** A DID means the participant already runs elsewhere — this environment only verifies it. */
  get external(): boolean {
    return this.did.trim().length > 0;
  }

  /**
   * Whether the BPN field is standing in for a derived value. Offered only for a participant this
   * environment onboards: an external system's BPN is agreed with its operator and has to be
   * typed, and there is no short name to derive it from anyway.
   */
  get derivingBpn(): boolean {
    return !this.external && this.deriveBpn;
  }

  /**
   * The BPN the run is submitted with. Mandatory in both modes, so the backend is never left to
   * invent one — empty here means the form is incomplete, not that a default applies.
   */
  get effectiveBpn(): string {
    return this.derivingBpn ? this.derivedBpn : this.bpn.trim();
  }

  get bpnHint(): string {
    if (this.external) {
      return 'Required — and it must be the BPN the system under test already runs with: '
        + 'its certificates are checked against the BPN in the credential issued here.';
    }
    return this.derivingBpn
      ? 'Required. Derived from the short name — enter one, or untick to type the BPN.'
      : 'Required.';
  }

  get canStart(): boolean {
    return !this.starting && this.effectiveBpn.length > 0;
  }

  /**
   * Leaving external mode must not silently discard a BPN typed while in it: derivation would
   * otherwise switch back on, disable the field and substitute the derived value with no cue
   * beyond the input greying out. A field with something in it keeps derivation off.
   */
  onDidChange(): void {
    if (!this.external && this.bpn.trim()) {
      this.deriveBpn = false;
    }
  }

  ensure(): void {
    this.ensuring = true;
    this.error = '';
    this.api.ensureVp().subscribe({
      next: status => {
        this.vp = status;
        this.ensuring = false;
      },
      error: err => {
        this.ensuring = false;
        this.error = friendly(err, 'Ensuring the verification participant failed');
      }
    });
  }

  startRun(): void {
    if (!this.canStart) {
      return;
    }
    this.starting = true;
    this.error = '';
    // The BPN always travels — it is mandatory on the form either way, so nothing here relies on
    // the backend deriving one. Name and short name stay the backend's to default, and for an
    // external participant they are not this environment's to state at all: it is already named
    // by the system that runs it, so the deactivated fields are not sent even if they hold text
    // typed before the DID was entered.
    const request: { name?: string; shortName?: string; bpn?: string; did?: string } = {
      bpn: this.effectiveBpn
    };
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
}

function friendly(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse) {
    const body = typeof err.error === 'string' ? err.error : err.error?.message;
    return body ? `${fallback}: ${body}` : `${fallback} (HTTP ${err.status})`;
  }
  return fallback;
}
