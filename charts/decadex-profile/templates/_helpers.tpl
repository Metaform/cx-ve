{{/* The in-cluster FQDN of a platform service in the release namespace. */}}
{{- define "decadex.fqdn" -}}
{{- printf "%s.%s.%s" .svc .ctx.Release.Namespace .ctx.Values.services.clusterDomain -}}
{{- end }}

{{/* The profile's trusted issuers: this platform's issuer first, then the configured ones, once each, trusted for every type. */}}
{{- define "decadex.trustedIssuers" -}}
{{- $dids := list .Values.issuer.did -}}
{{- range .Values.issuer.trustedIssuers }}{{- $dids = append $dids . -}}{{- end -}}
{{- $entries := list -}}
{{- range (uniq $dids) }}{{- $entries = append $entries (dict "@id" . "supportedTypes" (list "*")) -}}{{- end -}}
{{- toJson $entries -}}
{{- end }}

{{/* RFC 8693 exchange of the projected SA token, setting TOKEN; $1 = scope. */}}
{{- define "decadex.exchangeToken" -}}
SA_TOKEN=$(cat /var/run/secrets/jwtlet/token)
if [ -z "$SA_TOKEN" ]; then
  echo "ERROR: subject token at /var/run/secrets/jwtlet/token is empty"
  exit 1
fi
resp=$(curl -s -w "\n%{http_code}" -X POST "http://{{ include "decadex.fqdn" (dict "svc" .Values.services.jwtlet "ctx" .) }}:8080/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" \
  --data-urlencode "subject_token=${SA_TOKEN}" \
  --data-urlencode "subject_token_type=urn:ietf:params:oauth:token-type:jwt" \
  --data-urlencode "resource={{ .Values.issuer.participantContextId }}" \
  --data-urlencode "scope=${TOKEN_SCOPE}" \
  --data-urlencode "audience={{ .Values.tokenExchangeAudience }}")
status=$(printf '%s\n' "$resp" | tail -1)
body=$(printf '%s\n' "$resp" | sed '$d')
if [ "$status" -ge 400 ]; then
  echo "ERROR: Failed to exchange the SA token (HTTP $status): $body"
  exit 1
fi
TOKEN=$(printf '%s\n' "$body" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
  echo "ERROR: Failed to parse the exchanged token from: $body"
  exit 1
fi
{{- end }}

{{/* POST <label> <url> with the JSON body on stdin: 409 = already exists, >= 400 fails the job. */}}
{{- define "decadex.createFn" -}}
create() {
  resp=$(curl -s -w "\n%{http_code}" -X POST "$2" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data @-)
  status=$(printf '%s\n' "$resp" | tail -1)
  if [ "$status" -eq 409 ]; then
    echo "$1 already exists"
  elif [ "$status" -ge 400 ]; then
    echo "ERROR: Failed to create $1 (HTTP $status): $(printf '%s\n' "$resp" | sed '$d')"
    exit 1
  else
    echo "$1 created"
  fi
}
{{- end }}

{{/* The projected SA token volume the seed jobs exchange. */}}
{{- define "decadex.tokenVolume" -}}
- name: jwtlet-subject-token
  projected:
    sources:
      - serviceAccountToken:
          path: token
          audience: {{ .Values.seedJobs.subjectTokenAudience | quote }}
          expirationSeconds: {{ .Values.seedJobs.subjectTokenExpirationSeconds }}
{{- end }}
