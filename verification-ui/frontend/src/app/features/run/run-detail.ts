import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, catchError, forkJoin, of, switchMap, takeWhile, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import {
  completionSummary,
  dataspaceDisplayName,
  findDataspace,
  memberIdLabelOf,
  useCaseDisplayName
} from '../../core/catalog';
import {
  CatalogDataspace,
  ChecklistItem,
  EventlogRollup,
  RunSnapshot,
  StepSnapshot,
  stepLabel
} from '../../core/models';

@Component({
  selector: 'app-run-detail',
  imports: [DatePipe, RouterLink],
  templateUrl: './run-detail.html',
  styleUrl: './run-detail.css'
})
export class RunDetail implements OnInit, OnDestroy {

  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly subscriptions: Subscription[] = [];

  run: RunSnapshot | null = null;
  rollup: EventlogRollup | null = null;
  notFound = false;
  /** Only for names and the member-id label — the run's ids stand in until (or unless) it loads. */
  catalog: CatalogDataspace[] = [];

  readonly stepLabel = stepLabel;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.subscriptions.push(this.api.catalog()
      .pipe(catchError(() => of([])))
      .subscribe(catalog => this.catalog = catalog));
    // poll run + ledger together every 2s; the tick that delivers the terminal snapshot is the
    // last one (takeWhile checks before each tick), so the final ledger arrives with it
    this.subscriptions.push(timer(0, 2000).pipe(
      takeWhile(() => !this.terminal),
      switchMap(() => forkJoin({
        run: this.api.run(id),
        events: this.api.events(id).pipe(catchError(() => of({ events: [] } as EventlogRollup)))
      }).pipe(catchError(err => {
        if (err?.status === 404) {
          this.notFound = true;
        }
        return of(null);
      })))
    ).subscribe(result => {
      if (result) {
        this.run = result.run;
        this.rollup = result.events;
      }
    }));
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach(subscription => subscription.unsubscribe());
  }

  get terminal(): boolean {
    return this.notFound || (!!this.run && this.run.state !== 'RUNNING');
  }

  get dataspaceName(): string {
    return this.run ? dataspaceDisplayName(this.catalog, this.run.dataspace) : '';
  }

  get useCaseName(): string {
    return this.run ? useCaseDisplayName(this.catalog, this.run.dataspace, this.run.useCase) : '';
  }

  /** What the run's dataspace calls a member id — "BPN" in Catena-X. */
  get memberIdLabel(): string {
    return memberIdLabelOf(this.run ? findDataspace(this.catalog, this.run.dataspace) : null);
  }

  /** What a success of this run's use case established. */
  get completion(): string {
    return this.run ? completionSummary(this.run.useCase) : '';
  }

  get runningStep(): StepSnapshot | null {
    return this.run?.steps.find(step => step.status === 'RUNNING') ?? null;
  }

  get stepNumber(): number {
    if (!this.run) {
      return 0;
    }
    const index = this.run.steps.findIndex(step => step.status === 'RUNNING');
    return index >= 0 ? index + 1 : this.run.steps.length;
  }

  get missing(): ChecklistItem[] {
    return this.run?.checklist.filter(item => !item.satisfied) ?? [];
  }

  get events() {
    return this.rollup?.events ?? [];
  }

  duration(step: StepSnapshot): string {
    if (!step.startedAt || !step.finishedAt) {
      return '';
    }
    const seconds = Math.round((new Date(step.finishedAt).getTime() - new Date(step.startedAt).getTime()) / 1000);
    if (seconds < 1) {
      return '<1s';
    }
    if (seconds < 60) {
      return `${seconds}s`;
    }
    return `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`;
  }
}
