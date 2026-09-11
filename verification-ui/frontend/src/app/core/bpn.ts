/**
 * Client-side mirror of the BFF's BpnDeriver (which mirrors the e2e suite): BPN and VAT id
 * derived deterministically from the short name via Java's String.hashCode. Kept in sync by
 * hand — it only PREVIEWS what the server will derive; the server's derivation is authoritative
 * when the fields are submitted empty.
 */

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

export function vatIdFor(seed: string): string {
  return 'DE' + hex8(seed);
}
