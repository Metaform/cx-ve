1. Publishing a request: draft → documents → submit
   An onboarding request is always built in three steps:
- Create a draft: POST / with the request data as JSON. The response body contains the request id and its version. The request is now in status DRAFT.
- Add the documents: one POST /{requestId}/documents per document. The signed GTC and one signed use case agreement (UCA) for each entry of ucas are required. The other documents are optional.
- Submit: POST /{requestId}/submit. The TSP checks the whole draft. If anything is missing, the response is 422 and every problem is listed in errors. Otherwise, the request goes to SUBMITTED.

While the request is a draft, you can still correct it (replace data/documents, remove documents, or delete the draft).

2. After submission, the request is read-only for you
   Once a request is submitted, it belongs to the review. You can no longer change it; only the TSP operator can modify the request. If something needs correcting, only the operator can do it.

You can still follow its progress with GET /{requestId} (Statuses: SUBMITTED, UNDER_REVIEW, ON_HOLD, APPROVAL_IN_PROGRESS, APPROVED, REJECTED) and download its documents. Nothing is provisioned until an operator approves the request.

3. Things to know
- Versions: every response carries the request's version (in the body and ETag header). Every change must send it back in If-Match, otherwise the call is refused.
- Retries: Idempotency-Key is optional on create/submit and makes a retry safe.
- DID: the preferredDid must be new to the TSP.

The sequence diagram below sums up the flow:

```mermaid
sequenceDiagram
    autonumber
    actor CSP as CSP application
    participant GW as Dataplane
    participant TSP as TSP onboarding API
    actor OP as TSP operator

    Note over CSP,GW: Prerequisite: contract negotiated, http-pull transfer started,<br/>access token + data endpoint fetched

    rect rgba(100,150,255,0.12)
    Note over CSP,TSP: Step 1: Create the draft
    CSP->>GW: POST / (JSON request data)
    GW->>TSP: relay
    TSP-->>CSP: 201 { id, version, status: DRAFT } + ETag
    end

    rect rgba(100,200,120,0.12)
    Note over CSP,TSP: Step 2: Add the documents (one call each)
    loop gtc, uca per use case, optional docs
        CSP->>GW: POST /{id}/documents (multipart, If-Match: version)
        GW->>TSP: relay
        TSP-->>CSP: 201 { document, new version } + ETag
    end
    opt Corrections while DRAFT
        CSP->>TSP: PUT /{id} · PUT/DELETE /{id}/documents/{docId} (If-Match)
        TSP-->>CSP: 200 + new ETag
    end
    end

    rect rgba(255,170,80,0.12)
    Note over CSP,TSP: Step 3: Submit
    CSP->>GW: POST /{id}/submit (If-Match: version)
    GW->>TSP: relay
    alt draft incomplete
        TSP-->>CSP: 422 errors[] (draft unchanged, fix and resubmit)
    else draft complete
        TSP-->>CSP: 200 { status: SUBMITTED }
    end
    end

    rect rgba(220,90,90,0.12)
    Note over CSP,OP: Review: the request is now read-only for the CSP
    CSP-xTSP: PUT /{id} or any document change
    TSP-->>CSP: 422 request already submitted
    OP->>TSP: review, may edit the request and its documents
    OP->>TSP: approve or reject
    end

    loop Follow-up
        CSP->>GW: GET /{id}
        GW->>TSP: relay
        TSP-->>CSP: 200 { status: UNDER_REVIEW / ON_HOLD / APPROVED / REJECTED }
    end
```