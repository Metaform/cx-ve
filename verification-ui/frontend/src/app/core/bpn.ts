/**
 * Catena-X BPN derivation from a seed (the participant's short name): the e2e suite's formula
 * (VerificationEnvironmentE2eTest#bpnFor) over Java's String.hashCode, kept in sync by hand, so a
 * derived BPN is one operators know from e2e runs. A run-form convenience only — the BFF derives
 * no BPN (the member id is mandatory on every run), so what this returns is exactly what gets
 * submitted. It yields a BPN, so it applies only where member ids are BPNs: in {@link CATENA_X}.
 */

/** The dataspace whose member id is the BPN — the only one {@link bpnFor} may fill it in for. */
export const CATENA_X = 'catena-x';

/** Java String.hashCode over UTF-16 code units, with 32-bit overflow semantics. */
function javaHashCode(value: string): number {
  let hash = 0;
  for (let i = 0; i < value.length; i++) {
    hash = (Math.imul(31, hash) + value.charCodeAt(i)) | 0;
  }
  return hash;
}

function hex8(value: string): string {
  // JS Math.abs(-2^31) = 2^31 (no int overflow), whose hex "80000000" matches Java's
  // %08X of the un-negatable Integer.MIN_VALUE — the formulas agree on every input.
  return Math.abs(javaHashCode(value)).toString(16).toUpperCase().padStart(8, '0');
}

export function bpnFor(seed: string): string {
  return ('BPNL' + hex8(seed) + '000000').substring(0, 16);
}
