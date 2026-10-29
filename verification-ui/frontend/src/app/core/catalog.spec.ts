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

const DECADE_X_ID: MemberIdFormat = { label: 'DECADE-X-ID', pattern: 'DX-[0-9]{8}', example: 'DX-00000001' };

const CATALOG: CatalogDataspace[] = [
  {
    id: 'decade-x', displayName: 'DECADE-X', available: true, memberId: DECADE_X_ID,
    useCases: [
      { id: 'ccm', displayName: 'Company Certificate Management', available: true },
      { id: 'substance-tracing', displayName: 'Substance tracing', available: false }
    ]
  },
  { id: 'other-x', displayName: 'Other-X', available: false, memberId: null, useCases: [] }
];

describe('catalog', () => {

  describe('display names', () => {

    it('come from the catalog', () => {
      expect(dataspaceDisplayName(CATALOG, 'decade-x')).toBe('DECADE-X');
      expect(useCaseDisplayName(CATALOG, 'decade-x', 'ccm')).toBe('Company Certificate Management');
    });

    it('fall back to the ids the catalog does not know', () => {
      expect(dataspaceDisplayName(CATALOG, 'gaia-x')).toBe('gaia-x');
      expect(useCaseDisplayName(CATALOG, 'decade-x', 'pcf')).toBe('pcf');
      expect(useCaseDisplayName(CATALOG, 'gaia-x', 'ccm')).toBe('ccm');
      expect(dataspaceDisplayName([], 'decade-x')).toBe('decade-x');
    });
  });

  describe('memberIdLabelOf', () => {

    it("is the dataspace's own name for a member id", () => {
      expect(memberIdLabelOf(CATALOG[0])).toBe('DECADE-X-ID');
    });

    it('falls back to a generic one', () => {
      expect(memberIdLabelOf(CATALOG[1])).toBe('Member ID');
      expect(memberIdLabelOf(null)).toBe('Member ID');
      expect(memberIdLabelOf({ ...CATALOG[0], memberId: { ...DECADE_X_ID, label: ' ' } })).toBe('Member ID');
    });
  });

  describe('member-id validation', () => {

    it('accepts an id in the format', () => {
      expect(isValidMemberId(DECADE_X_ID, 'DX-00000001')).toBeTrue();
      expect(isValidMemberId(DECADE_X_ID, 'DX-40179701')).toBeTrue();
    });

    it('matches the WHOLE id, as the server does', () => {
      expect(isValidMemberId(DECADE_X_ID, 'DX-0000000')).toBeFalse();
      expect(isValidMemberId(DECADE_X_ID, 'DX-000000011')).toBeFalse();
      expect(isValidMemberId(DECADE_X_ID, 'xDX-00000001')).toBeFalse();
      expect(isValidMemberId(DECADE_X_ID, 'dx-00000001')).toBeFalse();
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
      expect(isValidMemberId({ ...DECADE_X_ID, pattern: null }, 'anything')).toBeTrue();
      expect(isValidMemberId({ ...DECADE_X_ID, pattern: '  ' }, 'anything')).toBeTrue();
    });

    it('reads a \\p{…} class as Java does, not as literal text', () => {
      const format: MemberIdFormat = { label: 'Id', pattern: '\\p{Lu}+', example: 'ÄB' };
      expect(isValidMemberId(format, 'ÄB')).toBeTrue();
      expect(isValidMemberId(format, 'äb')).toBeFalse();
    });

    it('leaves a pattern this browser cannot compile to the server', () => {
      // a possessive quantifier: valid Java, no JavaScript
      const format: MemberIdFormat = { ...DECADE_X_ID, pattern: 'DX-[0-9]++' };
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
