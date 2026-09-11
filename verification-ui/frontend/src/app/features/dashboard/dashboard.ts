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
    this.starting = true;
    this.error = '';
    const request: { name?: string; shortName?: string; bpn?: string } = {};
    if (this.name.trim()) {
      request.name = this.name.trim();
    }
    if (this.shortName.trim()) {
      request.shortName = this.shortName.trim();
    }
    if (!this.deriveBpn && this.bpn.trim()) {
      request.bpn = this.bpn.trim();
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
    if (state === 'PROVISIONED') {
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
