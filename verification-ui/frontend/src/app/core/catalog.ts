import { CatalogDataspace, CatalogUseCase, MemberIdFormat } from './models';

/** The company certificate management use case — a certificate exchange, in any dataspace. */
export const CCM = 'ccm';

/** What a member id is called where the dataspace does not say (or is not known). */
export const DEFAULT_MEMBER_ID_LABEL = 'Member ID';

/** An entry of the catalog that can be offered for selection: a dataspace or a use case. */
interface Option {
  id: string;
  available: boolean;
}

export function findDataspace(catalog: CatalogDataspace[], id: string | null): CatalogDataspace | null {
  return catalog.find(dataspace => dataspace.id === id) ?? null;
}

export function findUseCase(dataspace: CatalogDataspace | null, id: string | null): CatalogUseCase | null {
  return dataspace?.useCases.find(useCase => useCase.id === id) ?? null;
}

/** The dataspace's display name — its id while the catalog does not know it (not loaded, or gone). */
export function dataspaceDisplayName(catalog: CatalogDataspace[], id: string): string {
  return findDataspace(catalog, id)?.displayName ?? id;
}

/** The use case's display name — its id while the catalog does not know it. */
export function useCaseDisplayName(catalog: CatalogDataspace[], dataspaceId: string, useCaseId: string): string {
  return findUseCase(findDataspace(catalog, dataspaceId), useCaseId)?.displayName ?? useCaseId;
}

/** What the dataspace calls a member id — "BPN" in Catena-X. */
export function memberIdLabelOf(dataspace: CatalogDataspace | null): string {
  return dataspace?.memberId?.label?.trim() || DEFAULT_MEMBER_ID_LABEL;
}

/**
 * The member-id pattern as the server applies it — to the WHOLE id (Java's Pattern.matches), so
 * anchored here around a non-capturing group, which keeps an alternation from anchoring only its
 * outer branches. Null when there is nothing to check client-side: no pattern, or one this
 * browser's regex dialect cannot compile, which is then left to the server to judge.
 */
export function memberIdPattern(format: MemberIdFormat | null): RegExp | null {
  const pattern = format?.pattern;
  if (!pattern?.trim()) {
    return null;
  }
  try {
    // unicode mode, so a Java \p{…} class means the same here instead of matching literally
    return new RegExp(`^(?:${pattern})$`, 'u');
  } catch {
    return null;
  }
}

/** Whether the member id is in the dataspace's format. Says nothing about a missing one. */
export function isValidMemberId(format: MemberIdFormat | null, memberId: string): boolean {
  return memberIdPattern(format)?.test(memberId) ?? true;
}

/**
 * The option to select on the user's behalf: the first preferred id that can be picked, else the
 * only option that can — a choice with a single answer is not put to the user — else none.
 */
export function pickAvailable(options: Option[], ...preferred: (string | null | undefined)[]): string | null {
  for (const id of preferred) {
    if (id && options.some(option => option.id === id && option.available)) {
      return id;
    }
  }
  const available = options.filter(option => option.available);
  return available.length === 1 ? available[0].id : null;
}

/**
 * The selection after the catalog is (re)loaded: the current one stands for as long as the
 * catalog lists it — even while it is unavailable, because availability follows the Membership
 * Hub and a hiccup there must not move the selection out from under the user. Otherwise it is
 * picked anew, as {@link pickAvailable} does.
 */
export function keepOrPick(options: Option[], current: string | null,
                           ...preferred: (string | null | undefined)[]): string | null {
  return current !== null && options.some(option => option.id === current)
    ? current
    : pickAvailable(options, ...preferred);
}

/** What a successful run of the use case established, in the words of its outcome banner. */
export function completionSummary(useCase: string): string {
  return useCase === CCM ? 'Onboarding and certificate transfer completed' : 'Verification completed';
}
