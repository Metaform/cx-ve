import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApiService } from './api.service';
import { StartRunRequest } from './models';

describe('ApiService', () => {

  let api: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the catalog', () => {
    api.catalog().subscribe();
    const request = http.expectOne('api/catalog');
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it("reads the status of one dataspace's verification participant", () => {
    api.vpStatus('catena-x').subscribe();
    const request = http.expectOne(candidate => candidate.url === 'api/verification-participant');
    expect(request.request.method).toBe('GET');
    expect(request.request.urlWithParams).toBe('api/verification-participant?dataspace=catena-x');
    request.flush({ dataspace: 'catena-x', exists: false, membership: null, offerSeeded: false });
  });

  it("ensures one dataspace's verification participant", () => {
    api.ensureVp('decade-x').subscribe();
    const request = http.expectOne(candidate => candidate.url === 'api/verification-participant');
    expect(request.request.method).toBe('POST');
    expect(request.request.urlWithParams).toBe('api/verification-participant?dataspace=decade-x');
    request.flush({ dataspace: 'decade-x', exists: false, membership: null, offerSeeded: false });
  });

  it('starts a run with the request as its body', () => {
    const run: StartRunRequest = { dataspace: 'catena-x', useCase: 'ccm', memberId: 'BPNL000000000001' };
    api.startRun(run).subscribe();
    const request = http.expectOne({ method: 'POST', url: 'api/runs' });
    expect(request.request.body).toEqual(run);
    request.flush({ id: 'r1' });
  });
});
