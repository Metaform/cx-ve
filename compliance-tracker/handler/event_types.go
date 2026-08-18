package handler

import "encoding/json"

// Go representations of every event this agent can be delivered. They come from three producers
// sharing one stream and one envelope, but not one set of conventions.
//
// Most are EDC events: every concrete subclass of the EDC `Event` base class
// (org.eclipse.edc.spi.event.Event), published by the `events-nats` bridge as CloudEvents v1.0 in
// structured mode. The CloudEvent `type` is the payload's fully-qualified Java class name (e.g.
// "org.eclipse.edc.identityhub.spi.keypair.events.KeyPairAdded") and `data` holds the event
// payload — NOT the EventEnvelope, so the envelope's id/at do not appear here. The NATS subject is
// "events." + the event's Event.name() value, which is what the Subject* constants below spell out.
//
// The onboarding family instead comes from cx-ve's own Onboarding API
// (com.metaform.cxve.adapter.out.nats.NatsOnboardingEventPublisher). Its `type` is a reverse-DNS
// name per CX-0000 §2.3 ("org.catena-x.onboarding.<name>.v1") rather than a class name, and its
// `subject` carries the onboarding process id. That envelope also repeats the BPN and DID as the
// `sourcebpn` and `participantdid` extensions, not modelled here: lifecycleagent.CloudEvent drops
// top-level members it does not declare, and the payload carries both fields anyway.
//
// The certificate exchange family comes from Certo, the CX-0135 (CCM) certificate exchange
// service. Same reverse-DNS convention ("org.catena-x.ccm.CertificateExchange<Status>.v1"), and
// one payload shape for every leaf — the exchange's status change — so the family struct below IS
// the leaf payload; the subject (and the status field) say which occurrence it was. The envelope's
// `subject` carries the exchange id and `sourcebpn` the publishing side's BPN.
//
// The Java hierarchy is two levels deep: an abstract per-family base (KeyPairEvent, IssuanceEvent,
// ...) carrying the shared fields, and a concrete leaf per occurrence. That is mirrored here by
// embedding the family struct, which encoding/json flattens into the same JSON object.
//
// Naming: <JavaTypeName>Event, so KeyPairAdded -> KeyPairAddedEvent and OnboardingStarted ->
// OnboardingStartedEvent.

// ---------------------------------------------------------------------------------------------
// Subjects. Use these in the Process dispatch switch; for the EDC families they are
// "events." + Event.name().
// ---------------------------------------------------------------------------------------------

const (
	// Asset — org.eclipse.edc.connector.controlplane.asset.spi.event
	SubjectAssetCreated = "events.asset.created"
	SubjectAssetUpdated = "events.asset.updated"
	SubjectAssetDeleted = "events.asset.deleted"

	// ContractDefinition — org.eclipse.edc.connector.controlplane.contract.spi.event.contractdefinition
	SubjectContractDefinitionCreated = "events.contract.definition.created"
	SubjectContractDefinitionUpdated = "events.contract.definition.updated"
	SubjectContractDefinitionDeleted = "events.contract.definition.deleted"

	// ContractNegotiation — org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation
	SubjectContractNegotiationInitiated  = "events.contract.negotiation.initiated"
	SubjectContractNegotiationRequested  = "events.contract.negotiation.requested"
	SubjectContractNegotiationOffered    = "events.contract.negotiation.offered"
	SubjectContractNegotiationAccepted   = "events.contract.negotiation.accepted"
	SubjectContractNegotiationAgreed     = "events.contract.negotiation.agreed"
	SubjectContractNegotiationVerified   = "events.contract.negotiation.verified"
	SubjectContractNegotiationFinalized  = "events.contract.negotiation.finalized"
	SubjectContractNegotiationTerminated = "events.contract.negotiation.terminated"

	// PolicyDefinition — org.eclipse.edc.connector.controlplane.policy.spi.event
	SubjectPolicyDefinitionCreated = "events.policy.definition.created"
	SubjectPolicyDefinitionUpdated = "events.policy.definition.updated"
	SubjectPolicyDefinitionDeleted = "events.policy.definition.deleted"

	// TransferProcess — org.eclipse.edc.connector.controlplane.transfer.spi.event
	SubjectTransferProcessInitiated               = "events.transfer.process.initiated"
	SubjectTransferProcessProvisioned             = "events.transfer.process.provisioned"
	SubjectTransferProcessRequested               = "events.transfer.process.requested"
	SubjectTransferProcessStarted                 = "events.transfer.process.started"
	SubjectTransferProcessCompleted               = "events.transfer.process.completed"
	SubjectTransferProcessTerminated              = "events.transfer.process.terminated"
	SubjectTransferProcessSuspended               = "events.transfer.process.suspended"
	SubjectTransferProcessDeprovisioned           = "events.transfer.process.deprovisioned"
	SubjectTransferProcessPrepared                = "events.transfer.process.prepared"
	SubjectTransferProcessPreparationRequested    = "events.transfer.process.preparationRequested"
	SubjectTransferProcessDeprovisioningRequested = "events.transfer.process.deprovisioningRequested"
	// NOTE "resuming", not "resumed" — that is what TransferProcessResumed.name() returns in EDC.
	SubjectTransferProcessResumed = "events.transfer.process.resuming"

	// Secret — org.eclipse.edc.connector.secret.spi.event
	SubjectSecretCreated = "events.secret.created"
	SubjectSecretUpdated = "events.secret.updated"
	SubjectSecretDeleted = "events.secret.deleted"

	// DidDocument — org.eclipse.edc.identityhub.spi.did.events
	SubjectDidDocumentPublished   = "events.diddocument.published"
	SubjectDidDocumentUnpublished = "events.diddocument.unpublished"

	// KeyPair — org.eclipse.edc.identityhub.spi.keypair.events
	SubjectKeyPairAdded     = "events.keypair.added"
	SubjectKeyPairActivated = "events.keypair.activated"
	SubjectKeyPairRotated   = "events.keypair.rotated"
	SubjectKeyPairRevoked   = "events.keypair.revoked"

	// ParticipantContext — org.eclipse.edc.identityhub.spi.participantcontext.events
	SubjectParticipantContextCreated  = "events.participantcontext.created"
	SubjectParticipantContextUpdated  = "events.participantcontext.updated"
	SubjectParticipantContextDeleting = "events.participantcontext.deleting"
	SubjectParticipantContextDeleted  = "events.participantcontext.deleted"

	// CredentialOffer — org.eclipse.edc.identityhub.spi.verifiablecredentials.events
	SubjectCredentialOfferReceived = "events.credentialoffer.received"

	// Issuance — org.eclipse.edc.issuerservice.spi.issuance.events
	SubjectIssuanceReceived            = "events.issuance.received"
	SubjectIssuanceRequested           = "events.issuance.requested"
	SubjectIssuanceApproved            = "events.issuance.approved"
	SubjectIssuanceRejected            = "events.issuance.rejected"
	SubjectIssuanceErrored             = "events.issuance.errored"
	SubjectIssuanceCredentialGenerated = "events.issuance.credential.generated"
	SubjectIssuanceCredentialDelivered = "events.issuance.credential.delivered"

	// Onboarding — com.metaform.cxve.domain.model (cx-ve, not EDC)
	SubjectOnboardingStarted   = "events.onboarding.started"
	SubjectOnboardingCompleted = "events.onboarding.completed"

	// CertificateExchange — Certo (CX-0135/CCM, not EDC). One leaf per exchange status, split
	// over the two CX-0135 §2.1.3 state machines: Fulfillment (the certificate being produced)
	// and Acceptance (the certificate being reviewed by its receiver).
	SubjectCertificateExchangeRequested              = "events.certificate.exchange.requested"
	SubjectCertificateExchangeAcknowledged           = "events.certificate.exchange.acknowledged"
	SubjectCertificateExchangeCertificationRequested = "events.certificate.exchange.certificationRequested"
	SubjectCertificateExchangeFulfilled              = "events.certificate.exchange.fulfilled"
	SubjectCertificateExchangeDeclined               = "events.certificate.exchange.declined"
	SubjectCertificateExchangeFailed                 = "events.certificate.exchange.failed"
	SubjectCertificateExchangeRetrieved              = "events.certificate.exchange.retrieved"
	SubjectCertificateExchangeAccepted               = "events.certificate.exchange.accepted"
	SubjectCertificateExchangeRejected               = "events.certificate.exchange.rejected"
	SubjectCertificateExchangeErrored                = "events.certificate.exchange.errored"
)

// ---------------------------------------------------------------------------------------------
// Subject prefixes — one per family, for dispatching on the family rather than the individual
// occurrence. Each pairs with the family's base struct (SubjectPrefixIssuance -> IssuanceEvent),
// which is what every leaf in the family shares, so a leaf added upstream is decoded and handled
// without a new case. The two contract families need their second segment: "events.contract."
// alone would not separate definitions from negotiations.
// ---------------------------------------------------------------------------------------------

const (
	SubjectPrefixAsset               = "events.asset."
	SubjectPrefixContractDefinition  = "events.contract.definition."
	SubjectPrefixContractNegotiation = "events.contract.negotiation."
	SubjectPrefixPolicyDefinition    = "events.policy.definition."
	SubjectPrefixTransferProcess     = "events.transfer.process."
	SubjectPrefixSecret              = "events.secret."
	SubjectPrefixDidDocument         = "events.diddocument."
	SubjectPrefixKeyPair             = "events.keypair."
	SubjectPrefixParticipantContext  = "events.participantcontext."
	SubjectPrefixCredentialOffer     = "events.credentialoffer."
	SubjectPrefixIssuance            = "events.issuance."
	SubjectPrefixOnboarding          = "events.onboarding."
	SubjectPrefixCertificateExchange = "events.certificate.exchange."
)

// ---------------------------------------------------------------------------------------------
// Asset
// ---------------------------------------------------------------------------------------------

// AssetEvent carries the fields shared by all asset events.
type AssetEvent struct {
	AssetID              string `json:"assetId,omitempty"`
	ParticipantContextID string `json:"participantContextId,omitempty"`
}

type AssetCreatedEvent struct{ AssetEvent }
type AssetUpdatedEvent struct{ AssetEvent }
type AssetDeletedEvent struct{ AssetEvent }

// ---------------------------------------------------------------------------------------------
// ContractDefinition
// ---------------------------------------------------------------------------------------------

// ContractDefinitionEvent carries the fields shared by all contract definition events.
type ContractDefinitionEvent struct {
	ContractDefinitionID string `json:"contractDefinitionId,omitempty"`
	ParticipantContextID string `json:"participantContextId,omitempty"`
}

type ContractDefinitionCreatedEvent struct{ ContractDefinitionEvent }
type ContractDefinitionUpdatedEvent struct{ ContractDefinitionEvent }
type ContractDefinitionDeletedEvent struct{ ContractDefinitionEvent }

// ---------------------------------------------------------------------------------------------
// ContractNegotiation
// ---------------------------------------------------------------------------------------------

// ContractNegotiationEvent carries the fields shared by all contract negotiation events.
type ContractNegotiationEvent struct {
	ContractNegotiationID string `json:"contractNegotiationId,omitempty"`
	CounterPartyAddress   string `json:"counterPartyAddress,omitempty"`
	CounterPartyID        string `json:"counterPartyId,omitempty"`
	ParticipantContextID  string `json:"participantContextId,omitempty"`
	Protocol              string `json:"protocol,omitempty"`
}

type ContractNegotiationInitiatedEvent struct{ ContractNegotiationEvent }
type ContractNegotiationRequestedEvent struct{ ContractNegotiationEvent }
type ContractNegotiationOfferedEvent struct{ ContractNegotiationEvent }
type ContractNegotiationAcceptedEvent struct{ ContractNegotiationEvent }
type ContractNegotiationAgreedEvent struct{ ContractNegotiationEvent }
type ContractNegotiationVerifiedEvent struct{ ContractNegotiationEvent }

// ContractNegotiationFinalizedEvent carries the concluded agreement. Kept raw: a ContractAgreement
// embeds an ODRL policy, a JSON-LD graph nothing here needs to walk.
type ContractNegotiationFinalizedEvent struct {
	ContractNegotiationEvent
	ContractAgreement json.RawMessage `json:"contractAgreement,omitempty"`
}

type ContractNegotiationTerminatedEvent struct {
	ContractNegotiationEvent
	Reason string `json:"reason,omitempty"`
}

// ---------------------------------------------------------------------------------------------
// PolicyDefinition
// ---------------------------------------------------------------------------------------------

// PolicyDefinitionEvent carries the fields shared by all policy definition events.
type PolicyDefinitionEvent struct {
	PolicyDefinitionID   string `json:"policyDefinitionId,omitempty"`
	ParticipantContextID string `json:"participantContextId,omitempty"`
}

type PolicyDefinitionCreatedEvent struct{ PolicyDefinitionEvent }
type PolicyDefinitionUpdatedEvent struct{ PolicyDefinitionEvent }
type PolicyDefinitionDeletedEvent struct{ PolicyDefinitionEvent }

// ---------------------------------------------------------------------------------------------
// TransferProcess
// ---------------------------------------------------------------------------------------------

// TransferProcessEvent carries the fields shared by all transfer process events. Type here is the
// transfer type (e.g. "HttpData-PULL"), not the CloudEvent type.
type TransferProcessEvent struct {
	TransferProcessID    string `json:"transferProcessId,omitempty"`
	AssetID              string `json:"assetId,omitempty"`
	Type                 string `json:"type,omitempty"`
	ContractID           string `json:"contractId,omitempty"`
	ParticipantContextID string `json:"participantContextId,omitempty"`
	Protocol             string `json:"protocol,omitempty"`
}

type TransferProcessInitiatedEvent struct{ TransferProcessEvent }
type TransferProcessProvisionedEvent struct{ TransferProcessEvent }
type TransferProcessRequestedEvent struct{ TransferProcessEvent }
type TransferProcessCompletedEvent struct{ TransferProcessEvent }
type TransferProcessDeprovisionedEvent struct{ TransferProcessEvent }
type TransferProcessPreparedEvent struct{ TransferProcessEvent }
type TransferProcessPreparationRequestedEvent struct{ TransferProcessEvent }
type TransferProcessDeprovisioningRequestedEvent struct{ TransferProcessEvent }

type TransferProcessStartedEvent struct {
	TransferProcessEvent
	DataAddress *DataAddress `json:"dataAddress,omitempty"`
}

type TransferProcessTerminatedEvent struct {
	TransferProcessEvent
	Reason string `json:"reason,omitempty"`
}

type TransferProcessSuspendedEvent struct {
	TransferProcessEvent
	Reason string `json:"reason,omitempty"`
}

type TransferProcessResumedEvent struct {
	TransferProcessEvent
	Reason string `json:"reason,omitempty"`
}

// ---------------------------------------------------------------------------------------------
// Secret
// ---------------------------------------------------------------------------------------------

// SecretEvent carries the fields shared by all secret events. Unlike the other control-plane
// families it has no participantContextId.
type SecretEvent struct {
	SecretID string `json:"secretId,omitempty"`
}

type SecretCreatedEvent struct{ SecretEvent }
type SecretUpdatedEvent struct{ SecretEvent }
type SecretDeletedEvent struct{ SecretEvent }

// ---------------------------------------------------------------------------------------------
// DidDocument
// ---------------------------------------------------------------------------------------------

// DidDocumentEvent carries the fields shared by all DID document events.
type DidDocumentEvent struct {
	Did                  string `json:"did,omitempty"`
	ParticipantContextID string `json:"participantContextId,omitempty"`
}

type DidDocumentPublishedEvent struct{ DidDocumentEvent }
type DidDocumentUnpublishedEvent struct{ DidDocumentEvent }

// ---------------------------------------------------------------------------------------------
// KeyPair
// ---------------------------------------------------------------------------------------------

// KeyPairEvent carries the fields shared by all key pair events.
type KeyPairEvent struct {
	ParticipantContextID string           `json:"participantContextId,omitempty"`
	KeyPairResource      *KeyPairResource `json:"keyPairResource,omitempty"`
	KeyID                string           `json:"keyId,omitempty"`
}

type KeyPairAddedEvent struct {
	KeyPairEvent
	PublicKeySerialized string `json:"publicKeySerialized,omitempty"`
	Type                string `json:"type,omitempty"`
}

type KeyPairActivatedEvent struct {
	KeyPairEvent
	PublicKeySerialized string `json:"publicKeySerialized,omitempty"`
	Type                string `json:"type,omitempty"`
}

// KeyPairRotatedEvent describes the key being retired. NewKeyDescriptor is @Nullable in EDC — it is
// absent when the key is rotated without a successor.
type KeyPairRotatedEvent struct {
	KeyPairEvent
	NewKeyDescriptor *KeyDescriptor `json:"newKeyDescriptor,omitempty"`
}

// KeyPairRevokedEvent mirrors KeyPairRotatedEvent; NewKeyDescriptor is likewise nullable.
type KeyPairRevokedEvent struct {
	KeyPairEvent
	NewKeyDescriptor *KeyDescriptor `json:"newKeyDescriptor,omitempty"`
}

// ---------------------------------------------------------------------------------------------
// ParticipantContext
// ---------------------------------------------------------------------------------------------

// ParticipantContextEvent carries the fields shared by all participant context events.
type ParticipantContextEvent struct {
	ParticipantContextID string            `json:"participantContextId,omitempty"`
	TraceContext         map[string]string `json:"traceContext,omitempty"`
}

type ParticipantContextCreatedEvent struct {
	ParticipantContextEvent
	Manifest *ParticipantManifest `json:"manifest,omitempty"`
}

type ParticipantContextUpdatedEvent struct {
	ParticipantContextEvent
	NewState ParticipantContextState `json:"newState,omitempty"`
}

// ParticipantContextDeletingEvent carries the context as it was immediately before deletion. Kept
// raw: IdentityHubParticipantContext is a large aggregate and nothing here needs to walk it.
type ParticipantContextDeletingEvent struct {
	ParticipantContextEvent
	ParticipantContext json.RawMessage `json:"participantContext,omitempty"`
}

type ParticipantContextDeletedEvent struct{ ParticipantContextEvent }

// ---------------------------------------------------------------------------------------------
// CredentialOffer
// ---------------------------------------------------------------------------------------------

// CredentialOfferEvent carries the fields shared by all credential offer events.
type CredentialOfferEvent struct {
	ParticipantContextID string `json:"participantContextId,omitempty"`
	Issuer               string `json:"issuer,omitempty"`
	ID                   string `json:"id,omitempty"`
}

type CredentialOfferReceivedEvent struct{ CredentialOfferEvent }

// ---------------------------------------------------------------------------------------------
// Issuance
// ---------------------------------------------------------------------------------------------

// IssuanceEvent carries the fields shared by all issuance events. This family has NO
// participantContextId: the issuer side is IssuerParticipantContextID and the holder is HolderID.
type IssuanceEvent struct {
	HolderID                   string `json:"holderId,omitempty"`
	IssuerParticipantContextID string `json:"issuerParticipantContextId,omitempty"`
	HolderProcessID            string `json:"holderProcessId,omitempty"`
	IssuanceProcessID          string `json:"issuanceProcessId,omitempty"`
}

type IssuanceApprovedEvent struct{ IssuanceEvent }

type IssuanceReceivedEvent struct {
	IssuanceEvent
	RequestedFormats map[string]CredentialFormat `json:"requestedFormats,omitempty"`
}

type IssuanceRequestedEvent struct {
	IssuanceEvent
	CredentialDefinitionIDs []string                    `json:"credentialDefinitionIds,omitempty"`
	CredentialFormats       map[string]CredentialFormat `json:"credentialFormats,omitempty"`
}

type IssuanceRejectedEvent struct {
	IssuanceEvent
	Reason string `json:"reason,omitempty"`
}

// IssuanceProcessErroredEvent is published on SubjectIssuanceErrored ("events.issuance.errored") —
// the subject does not follow the class name here.
type IssuanceProcessErroredEvent struct {
	IssuanceEvent
	ErrorMessage string `json:"errorMessage,omitempty"`
}

// CredentialGeneratedEvent carries the freshly minted credentials. They stay raw: a
// VerifiableCredential is a JSON-LD graph whose shape depends on the credential type.
type CredentialGeneratedEvent struct {
	IssuanceEvent
	Credentials []json.RawMessage `json:"credentials,omitempty"`
}

// CredentialDeliveredEvent signals credentials reached the holder — the event the Onboarding API
// treats as onboarding completion.
type CredentialDeliveredEvent struct {
	IssuanceEvent
	Credentials []json.RawMessage `json:"credentials,omitempty"`
}

// ---------------------------------------------------------------------------------------------
// Onboarding
// ---------------------------------------------------------------------------------------------

// OnboardingEvent carries the fields shared by both onboarding events. ProcessID is the correlation
// key between them (and is also the CloudEvent subject); ExternalID is the id the onboarding service
// provider submitted the registration under.
type OnboardingEvent struct {
	ProcessID  string `json:"processId,omitempty"`
	ExternalID string `json:"externalId,omitempty"`
	Bpn        string `json:"bpn,omitempty"`
	Did        string `json:"did,omitempty"`
}

// OnboardingStartedEvent announces an accepted registration, before any provisioning has happened.
// The DID is already final — derived from the participant DID template by the same rule
// provisioning will use. The BPN is final only when the registration submitted one; it is empty
// for a registration without a BPN (one gets assigned during onboarding and arrives on the
// completed event), so the BPN↔DID binding this event establishes is conditional on Bpn being set.
type OnboardingStartedEvent struct{ OnboardingEvent }

// OnboardingCompletedEvent announces that an onboarding reached a terminal state — any of them, not
// just success — so State is what says how it ended and must be inspected. Did and
// ParticipantContextID are empty when the onboarding never got far enough to be assigned them, and
// FailureMessage is set for OnboardingStateRejected and OnboardingStateFailed.
type OnboardingCompletedEvent struct {
	OnboardingEvent
	ParticipantContextID string          `json:"participantContextId,omitempty"`
	State                OnboardingState `json:"state,omitempty"`
	FailureMessage       string          `json:"failureMessage,omitempty"`
}

// ---------------------------------------------------------------------------------------------
// CertificateExchange
// ---------------------------------------------------------------------------------------------

// CertificateExchangeEvent is the payload of EVERY certificate exchange leaf — Certo publishes
// one record shape, the exchange's status change, for all of them. In the VE a single Certo
// instance serves both sides of an exchange, so the same logical exchange produces one event per
// side: Role says whose perspective this is, and ParticipantContextID is that side's own context.
// The counterparty fields identify the OTHER side — a different participant, which is why the
// handler must not promote them as this event's correlation keys.
type CertificateExchangeEvent struct {
	Role                 string `json:"role,omitempty"`  // PROVIDER | CONSUMER
	Phase                string `json:"phase,omitempty"` // FULFILLMENT | ACCEPTANCE
	ExchangeID           string `json:"exchangeId,omitempty"`
	ParticipantContextID string `json:"participantContextId,omitempty"`
	CounterpartyBpn      string `json:"counterpartyBpn,omitempty"`
	CounterpartyDid      string `json:"counterpartyDid,omitempty"`
	// CertificateID is empty while the exchange has not produced one (pending request).
	CertificateID string `json:"certificateId,omitempty"`
	Revision      int    `json:"revision,omitempty"`
	// PreviousStatus is empty when the exchange was just opened rather than transitioned.
	PreviousStatus string `json:"previousStatus,omitempty"`
	// Status repeats what the subject leaf says, as the state machine's constant name
	// (e.g. FULFILLED, CERTIFICATION_REQUESTED).
	Status string `json:"status,omitempty"`
}

// ---------------------------------------------------------------------------------------------
// Supporting value types
// ---------------------------------------------------------------------------------------------

// ParticipantContextState is serialized by Jackson as the enum constant name — there is no
// @JsonValue on its int code — so it is a string on the wire.
type ParticipantContextState string

const (
	ParticipantContextStateCreated     ParticipantContextState = "CREATED"
	ParticipantContextStateActivated   ParticipantContextState = "ACTIVATED"
	ParticipantContextStateDeactivated ParticipantContextState = "DEACTIVATED"
)

// OnboardingState is the terminal state reached by an onboarding, likewise serialized as the enum
// constant name. Only these three appear on the wire: the Onboarding API announces an outcome
// exactly when the process becomes terminal, so the intermediate states of its state machine
// (SUBMITTED, VALIDATED, BPN_ASSIGNED, ...) are never published.
type OnboardingState string

const (
	OnboardingStateCompleted OnboardingState = "COMPLETED"
	OnboardingStateRejected  OnboardingState = "REJECTED"
	OnboardingStateFailed    OnboardingState = "FAILED"
)

// CredentialFormat is likewise serialized as the enum constant name.
type CredentialFormat string

const (
	CredentialFormatVC10LD    CredentialFormat = "VC1_0_LD"
	CredentialFormatVC10JWT   CredentialFormat = "VC1_0_JWT"
	CredentialFormatVC20JOSE  CredentialFormat = "VC2_0_JOSE"
	CredentialFormatVC20SDJWT CredentialFormat = "VC2_0_SD_JWT"
	CredentialFormatVC20COSE  CredentialFormat = "VC2_0_COSE"
)

// KeyPairResource is the IdentityHub key pair record embedded in every KeyPairEvent.
type KeyPairResource struct {
	KeyID                string `json:"keyId,omitempty"`
	SerializedPublicKey  string `json:"serializedPublicKey,omitempty"`
	PrivateKeyAlias      string `json:"privateKeyAlias,omitempty"`
	ParticipantContextID string `json:"participantContextId,omitempty"`
	GroupName            string `json:"groupName,omitempty"`
	KeyContext           string `json:"keyContext,omitempty"`
	DefaultPair          bool   `json:"defaultPair,omitempty"`
	UseDuration          int64  `json:"useDuration,omitempty"`
	RotationDuration     int64  `json:"rotationDuration,omitempty"`
	Timestamp            int64  `json:"timestamp,omitempty"`
	// State is the numeric participant-resource state code, not a string.
	State int `json:"state,omitempty"`
}

// KeyDescriptor describes a key being introduced — e.g. the successor on rotation or revocation.
type KeyDescriptor struct {
	KeyID              string         `json:"keyId,omitempty"`
	Type               string         `json:"type,omitempty"`
	PrivateKeyAlias    string         `json:"privateKeyAlias,omitempty"`
	PublicKeyJwk       map[string]any `json:"publicKeyJwk,omitempty"`
	PublicKeyPem       string         `json:"publicKeyPem,omitempty"`
	KeyGeneratorParams map[string]any `json:"keyGeneratorParams,omitempty"`
}

// ParticipantManifest is the creation-time descriptor on ParticipantContextCreatedEvent.
type ParticipantManifest struct {
	ParticipantID    string          `json:"participantId,omitempty"`
	Did              string          `json:"did,omitempty"`
	Active           bool            `json:"active,omitempty"`
	Key              *KeyDescriptor  `json:"key,omitempty"`
	Roles            []string        `json:"roles,omitempty"`
	ServiceEndpoints json.RawMessage `json:"serviceEndpoints,omitempty"`
}

// DataAddress is the destination on TransferProcessStartedEvent. Its schema is open — the concrete
// keys depend on the transfer type — so the properties bag stays generic.
type DataAddress struct {
	Properties map[string]any `json:"properties,omitempty"`
}
