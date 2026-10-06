import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CatalogDataspace, EventlogRollup, RunSnapshot, RunSummary, StartRunRequest, VpStatus } from './models';

/**
 * The BFF's API — strictly same-origin relative URLs: behind the gateway the app lives under
 * /ui with the prefix stripped, in dev `ng serve` proxies /api to the local BFF. The browser
 * never talks to the platform directly.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {

  private readonly http = inject(HttpClient);

  /** What a run can be started for: the dataspaces, their use cases and member-id formats. */
  catalog(): Observable<CatalogDataspace[]> {
    return this.http.get<CatalogDataspace[]>('api/catalog');
  }

  /** The verification participant of one dataspace — each has its own. */
  vpStatus(dataspace: string): Observable<VpStatus> {
    return this.http.get<VpStatus>('api/verification-participant', { params: { dataspace } });
  }

  /** Synchronous on the server — a first-time ensure runs the whole onboarding (minutes). */
  ensureVp(dataspace: string): Observable<VpStatus> {
    return this.http.post<VpStatus>('api/verification-participant', {}, { params: { dataspace } });
  }

  /**
   * Answers 400 with a plain-text reason for a dataspace, use case or member id the catalog
   * refuses.
   */
  startRun(request: StartRunRequest): Observable<RunSnapshot> {
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
