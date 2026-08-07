package handler

// ComplianceEventData is the payload carried in the CloudEvent's `data` field.
//
// These are the identity fields common to CFM lifecycle events; extend the struct as the
// compliance rules take shape. Fields that are not modelled here are NOT lost: the framework
// hands the undecoded message body to the processor as EventContext.Raw, so a handler can always
// re-parse the original JSON for subject-specific fields (see the keypair handler for an example).
//
// Note that a payload which fails to unmarshal into this type causes the message to be dropped
// (common/natsclient/processor.go acks undecodable messages), so keep every field optional.
type ComplianceEventData struct {
	// ParticipantContextID identifies the participant context the event pertains to. Used by
	// IdentityHub-sourced events (issuance, keypair).
	ParticipantContextID string `json:"participantContextId,omitempty"`
	// ParticipantID is the CFM participant identifier, as set on provisioning activity payloads.
	ParticipantID string `json:"cfm.participant.id,omitempty"`
	// ID is the identifier of the domain object the event is about (a credential, a key, ...).
	ID string `json:"id,omitempty"`
}
