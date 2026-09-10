/** TS mirrors of the BFF's wire shapes (see the Java records in com.metaform.cxve.verification). */

export interface Membership {
  externalId: string;
  name: string;
  did: string | null;
  bpn: string;
  state: string;
  onboardingProcessId: string | null;
  tenantId: string | null;
  participantProfileId: string | null;
  participantContextId: string | null;
  failureReason: string | null;
}

export interface VpStatus {
  exists: boolean;
  membership: Membership | null;
  offerSeeded: boolean;
}

export interface VerificationParticipant {
  externalId: string;
  name: string;
  bpn: string;
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
  bpn: string;
  vatId: string;
  externalId: string | null;
  did: string | null;
  participantContextId: string | null;
  onboardingProcessId: string | null;
}

export interface RunSnapshot {
  id: string;
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
  state: RunState;
  currentStep: string | null;
  startedAt: string;
  finishedAt: string | null;
  name: string;
  shortName: string;
  bpn: string;
  externalId: string | null;
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

/** Friendly labels for the run's step enum — the sequence the timeline renders. */
export const STEP_LABELS: Record<string, string> = {
  ENSURE_VERIFICATION_PARTICIPANT: 'Ensure verification participant',
  ONBOARD_PARTICIPANT: 'Onboard participant',
  AWAIT_PROVISIONED: 'Await provisioning',
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
