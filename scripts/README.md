# Scripts

Utility and automation scripts for the cx-ve project. Each script documents its own options in a header comment and
supports `--help`.

## `setup-did-dns.sh`

Makes the platform's gateway hostnames resolvable from **inside** the cluster, by adding `rewrite`
rules to CoreDNS that point every HTTPRoute hostname at the Traefik Service, then verifies both DNS and an end-to-end
`did:web` resolution.

Needed because `core-platform-distribution` advertises itself under its gateway hostname: the DSP callback
address, the DCP credential-service and issuance endpoints and the `did:web` identifiers all point there
rather than at in-cluster Service FQDNs, and the runtimes dereference those URLs themselves — the control plane
resolves the issuer DID to verify credential signatures and calls the CredentialService on every DSP message. A
`*.localhost` name otherwise resolves to the pod's own loopback and every one of those lookups fails.

```bash
./scripts/setup-did-dns.sh -c ve1               # apply + verify
./scripts/setup-did-dns.sh -c ve1 --verify-only # dry-run
```

Re-run it after `install-ve.sh`, since recreating the KinD cluster discards the rewrites. It is idempotent — the rules
live between marker comments in the Corefile and are replaced wholesale.

Everything but the cluster name is discovered from the cluster (DNS domain, Traefik Service, hostname list), so the
rules cannot drift from what is deployed. That matters most for the DNS domain: each VE gets its own (`ve1.local`,
`ve2.local`), and a rewrite target under `cluster.local` silently NXDOMAINs because CoreDNS is not authoritative for it.

> For the dual-VE setup each cluster additionally needs the **peer's** hostnames rewritten to
> `traefik.traefik.svc.<peer-dns-domain>`, which resolves over the zone forwarding and static
> routes `connect-ves.sh` installs. That is not handled yet.
