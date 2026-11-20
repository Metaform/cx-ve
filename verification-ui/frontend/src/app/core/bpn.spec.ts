import { bpnFor } from './bpn';

// The expected values are the Java formula's own output (VerificationEnvironmentE2eTest#bpnFor),
// so these pin the hand-kept port to it.
describe('bpnFor', () => {

  it('derives the BPN the e2e suite derives', () => {
    expect(bpnFor('verification-participant')).toBe('BPNL40C1797F0000');
    expect(bpnFor('provider-abc')).toBe('BPNL190C6D7A0000');
  });

  it('zero-pads a small hash', () => {
    expect(bpnFor('acme')).toBe('BPNL002D993A0000');
  });

  it('hashes UTF-16 code units, as Java does', () => {
    expect(bpnFor('Ä-umlaut')).toBe('BPNL5D47A56B0000');
  });

  it('agrees with Java on the one hash Math.abs cannot negate', () => {
    // "polygenelubricants".hashCode() is Integer.MIN_VALUE
    expect(bpnFor('polygenelubricants')).toBe('BPNL800000000000');
  });

  it('yields an id in the Catena-X member-id format', () => {
    expect(bpnFor('put-1a2b3c4d')).toMatch(/^BPNL[0-9A-Z]{12}$/);
  });
});
