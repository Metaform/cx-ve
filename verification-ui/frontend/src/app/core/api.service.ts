import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { EventlogRollup, RunSnapshot, RunSummary, VpStatus } from './models';

/**
 * The BFF's API — strictly same-origin relative URLs: behind the gateway the app lives under
 * /ui with the prefix stripped, in dev `ng serve` proxies /api to the local BFF. The browser
 * never talks to the platform directly.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {

  private readonly http = inject(HttpClient);

  vpStatus(): Observable<VpStatus> {
    return this.http.get<VpStatus>('api/verification-participant');
  }

  /** Synchronous on the server — a first-time ensure runs the whole onboarding (minutes). */
  ensureVp(): Observable<VpStatus> {
    return this.http.post<VpStatus>('api/verification-participant', {});
  }

  startRun(request: { name?: string; shortName?: string; bpn?: string }): Observable<RunSnapshot> {
    return this.http.post<RunSnapshot>('api/runs', request);
  }

  runs(): Observable<RunSummary[]> {
    return this.http.get<RunSummary[]>('api/runs');
  }

  run(id: string): Observable<RunSnapshot> {
    return this.http.get<RunSnapshot>(`api/runs/${id}`);
  }

  events(id: string): Observable<EventlogRollup> {
    return this.http.get<EventlogRollup>(`api/runs/${id}/events`);
  }
}
