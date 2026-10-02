import {
  completionSummary,
  dataspaceDisplayName,
  isValidMemberId,
  keepOrPick,
  memberIdLabelOf,
  memberIdPattern,
  pickAvailable,
  useCaseDisplayName
} from './catalog';
import { CatalogDataspace, MemberIdFormat } from './models';

const BPN: MemberIdFormat = { label: 'BPN', pattern: 'BPNL[0-9A-Z]{12}', example: 'BPNL000000000001' };

const CATALOG: CatalogDataspace[] = [
  {
    id: 'catena-x', displayName: 'Catena-X', available: true, memberId: BPN,
    useCases: [
      { id: 'ccm', displayName: 'Company Certificate Management', available: true },
      { id: 'traceability', displayName: 'Traceability', available: false }
    ]
  },
  { id: 'decade-x', displayName: 'Decade-X', available: false, memberId: null, useCases: [] }
];

describe('catalog', () => {

  describe('display names', () => {

    it('come from the catalog', () => {
      expect(dataspaceDisplayName(CATALOG, 'catena-x')).toBe('Catena-X');
      expect(useCaseDisplayName(CATALOG, 'catena-x', 'ccm')).toBe('Company Certificate Management');
    });

    it('fall back to the ids the catalog does not know', () => {
      expect(dataspaceDisplayName(CATALOG, 'gaia-x')).toBe('gaia-x');
      expect(useCaseDisplayName(CATALOG, 'catena-x', 'pcf')).toBe('pcf');
      expect(useCaseDisplayName(CATALOG, 'gaia-x', 'ccm')).toBe('ccm');
      expect(dataspaceDisplayName([], 'catena-x')).toBe('catena-x');
    });
  });

  describe('memberIdLabelOf', () => {

    it("is the dataspace's own name for a member id", () => {
      expect(memberIdLabelOf(CATALOG[0])).toBe('BPN');
    });

    it('falls back to a generic one', () => {
      expect(memberIdLabelOf(CATALOG[1])).toBe('Member ID');
      expect(memberIdLabelOf(null)).toBe('Member ID');
      expect(memberIdLabelOf({ ...CATALOG[0], memberId: { ...BPN, label: ' ' } })).toBe('Member ID');
    });
  });

  describe('member-id validation', () => {

    it('accepts an id in the format', () => {
      expect(isValidMemberId(BPN, 'BPNL000000000001')).toBeTrue();
      expect(isValidMemberId(BPN, 'BPNL40C1797F0000')).toBeTrue();
    });

    it('matches the WHOLE id, as the server does', () => {
      expect(isValidMemberId(BPN, 'BPNL00000000000')).toBeFalse();
      expect(isValidMemberId(BPN, 'BPNL0000000000011')).toBeFalse();
      expect(isValidMemberId(BPN, 'xBPNL000000000001')).toBeFalse();
      expect(isValidMemberId(BPN, 'bpnl000000000001')).toBeFalse();
    });

    it('anchors every branch of an alternation', () => {
      const format: MemberIdFormat = { label: 'Id', pattern: 'AB|CD', example: 'AB' };
      expect(isValidMemberId(format, 'AB')).toBeTrue();
      expect(isValidMemberId(format, 'CD')).toBeTrue();
      expect(isValidMemberId(format, 'ABX')).toBeFalse();
      expect(isValidMemberId(format, 'XCD')).toBeFalse();
    });

    it('accepts anything when the dataspace sets no pattern', () => {
      expect(isValidMemberId(null, 'anything')).toBeTrue();
      expect(isValidMemberId({ ...BPN, pattern: null }, 'anything')).toBeTrue();
      expect(isValidMemberId({ ...BPN, pattern: '  ' }, 'anything')).toBeTrue();
    });

    it('reads a \\p{…} class as Java does, not as literal text', () => {
      const format: MemberIdFormat = { label: 'Id', pattern: '\\p{Lu}+', example: 'ÄB' };
      expect(isValidMemberId(format, 'ÄB')).toBeTrue();
      expect(isValidMemberId(format, 'äb')).toBeFalse();
    });

    it('leaves a pattern this browser cannot compile to the server', () => {
      // a possessive quantifier: valid Java, no JavaScript
      const format: MemberIdFormat = { ...BPN, pattern: 'BPNL[0-9A-Z]++' };
      expect(memberIdPattern(format)).toBeNull();
      expect(isValidMemberId(format, 'anything')).toBeTrue();
    });
  });

  describe('pickAvailable', () => {

    const options = [
      { id: 'a', available: true },
      { id: 'b', available: true },
      { id: 'c', available: false }
    ];

    it('takes the first preferred option that can be picked', () => {
      expect(pickAvailable(options, 'c', null, 'b', 'a')).toBe('b');
    });

    it('selects the only option that can be picked', () => {
      expect(pickAvailable([{ id: 'a', available: false }, { id: 'b', available: true }])).toBe('b');
    });

    it('leaves a real choice to the user', () => {
      expect(pickAvailable(options)).toBeNull();
      expect(pickAvailable(options, 'gone', 'c')).toBeNull();
      expect(pickAvailable([{ id: 'a', available: false }])).toBeNull();
      expect(pickAvailable([])).toBeNull();
    });
  });

  describe('keepOrPick', () => {

    it('keeps the current selection, even once it is unavailable', () => {
      const options = [{ id: 'a', available: false }, { id: 'b', available: true }];
      expect(keepOrPick(options, 'a', 'b')).toBe('a');
    });

    it('picks anew when the current selection is gone, or there is none', () => {
      const options = [{ id: 'a', available: true }, { id: 'b', available: true }];
      expect(keepOrPick(options, 'gone', 'b')).toBe('b');
      expect(keepOrPick(options, null, 'a')).toBe('a');
      expect(keepOrPick([{ id: 'a', available: true }], null)).toBe('a');
    });
  });

  describe('completionSummary', () => {

    it('speaks of the certificate transfer for CCM', () => {
      expect(completionSummary('ccm')).toBe('Onboarding and certificate transfer completed');
    });

    it('stays generic for any other use case', () => {
      expect(completionSummary('traceability')).toBe('Verification completed');
    });
  });
});
