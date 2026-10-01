/** TS mirrors of the BFF's wire shapes (see the Java records in com.metaform.cxve.verification). */

/**
 * How a dataspace names and formats a member's id (Catena-X: the BPN) — the run form's label,
 * placeholder and validation.
 */
export interface MemberIdFormat {
  /** What the dataspace calls a member id, e.g. "BPN". */
  label: string;
  /** A regular expression a member id must match in full; blank (or unset) accepts anything. */
  pattern: string | null;
  /** A sample value, shown as the input's placeholder. */
  example: string;
}

export interface CatalogUseCase {
  id: string;
  displayName: string;
  /** False for a use case the dataspace lists but this environment cannot verify (yet). */
  available: boolean;
}

/** A dataspace as GET api/catalog lists it — in the order the UI offers them. */
export interface CatalogDataspace {
  id: string;
  displayName: string;
  /** False while the Membership Hub does not serve the dataspace: nothing in it can be run. */
  available: boolean;
  /** Null when the dataspace's profile sets no format — any member id is accepted then. */
  memberId: MemberIdFormat | null;
  useCases: CatalogUseCase[];
}

/**
 * A run of a use case in a dataspace, both by their catalog ids. The member id is mandatory; a
 * `did` makes it a run against a third-party system, without one the participant is onboarded here.
 */
export interface StartRunRequest {
  dataspace: string;
  useCase: string;
  name?: string;
  shortName?: string;
  memberId: string;
  did?: string;
}

export interface Membership {
  externalId: string;
  dataspace: string;
  name: string;
  did: string | null;
  memberId: string;
  state: string;
  onboardingProcessId: string | null;
  tenantId: string | null;
  participantProfileId: string | null;
  participantContextId: string | null;
  failureReason: string | null;
}

/** A dataspace's permanent verification participant — there is one per dataspace. */
export interface VpStatus {
  dataspace: string;
  exists: boolean;
  membership: Membership | null;
  offerSeeded: boolean;
}

export interface VerificationParticipant {
  externalId: string;
  dataspace: string;
  name: string;
  memberId: string;
  did: string;
  participantContextId: string;
}

export type RunState = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export type StepStatus = 'PENDING' | 'RUNNING' | 'OK' | 'FAILED' | 'SKIPPED';

export interface StepSnapshot {
  step: string;
  status: StepStatus;
  detail: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface ChecklistItem {
  subject: string;
  minCount: number;
  actualCount: number;
  satisfied: boolean;
}

export interface ParticipantInfo {
  name: string;
  shortName: string;
  /** The participant's id within the run's dataspace — the BPN in Catena-X. */
  memberId: string;
  /** The registration's unique id (e.g. a VAT id), derived by the BFF from the short name. */
  uniqueId: string;
  externalId: string | null;
  did: string | null;
  participantContextId: string | null;
  onboardingProcessId: string | null;
  /** True when the participant runs outside this environment — a null context id is then normal. */
  externallyHosted: boolean;
}

export interface RunSnapshot {
  id: string;
  /** The catalog ids of what the run verifies: the dataspace, and the use case within it. */
  dataspace: string;
  useCase: string;
  state: RunState;
  startedAt: string;
  finishedAt: string | null;
  participant: ParticipantInfo;
  verificationParticipant: VerificationParticipant | null;
  steps: StepSnapshot[];
  checklist: ChecklistItem[];
  failureReason: string | null;
  failedStep: string | null;
}

export interface RunSummary {
  id: string;
  dataspace: string;
  useCase: string;
  state: RunState;
  currentStep: string | null;
  startedAt: string;
  finishedAt: string | null;
  name: string;
  shortName: string;
  memberId: string;
  externalId: string | null;
  externallyHosted: boolean;
}

export interface EventSummary {
  occurredAt: string;
  subject: string;
  type: string;
  source: string;
  eventId: string;
}

/** The hub's participant_eventlog rollup, proxied through the BFF; events is [] until known. */
export interface EventlogRollup {
  processId?: string;
  externalId?: string;
  bpn?: string;
  did?: string;
  participantContextId?: string;
  state?: string;
  eventCount?: number;
  events: EventSummary[] | null;
}

/**
 * Friendly labels for the run's step enum. A run carries only the steps of its own sequence, so
 * this covers both; unknown keys fall back to the raw name.
 */
export const STEP_LABELS: Record<string, string> = {
  ENSURE_VERIFICATION_PARTICIPANT: 'Ensure verification participant',
  RESOLVE_DID: 'Resolve participant DID',
  ONBOARD_PARTICIPANT: 'Onboard participant',
  AWAIT_PROVISIONED: 'Await provisioning',
  AWAIT_CREDENTIAL_OFFER: 'Offer credentials',
  AWAIT_CREDENTIALS: 'Await credential delivery',
  AWAIT_PUBLISHED_CERTIFICATE: 'Await pushed certificate',
  AWAIT_CERTO_CONTEXT: 'Await certificate tenant',
  SEED_PROVIDER_OFFER: 'Seed provider offer',
  ESTABLISH_PULL_FLOW: 'Establish pull flow',
  ESTABLISH_PUSH_FLOW: 'Establish push flow',
  PUBLISH_CERTIFICATE: 'Publish certificate',
  RETRIEVE_AND_VERIFY: 'Retrieve & verify document',
  ACCEPT: 'Accept certificate',
  EVALUATE_EVENTS: 'Evaluate event ledger'
};

export function stepLabel(step: string | null): string {
  return step ? (STEP_LABELS[step] ?? step) : '';
}
