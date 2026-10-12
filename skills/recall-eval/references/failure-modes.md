# Recall Agent diagnostic reference

The runtime is recall-server (Java/Spring Boot), not the legacy root teaching app.
Check the actual implementation when diagnosing a live version. This reference
describes the reviewed-memory/hybrid-retrieval version.

| Dimension | Evidence to compare | Frequent diagnostic trap |
| --- | --- | --- |
| Retrieval | Current workspace, active facts, recall mode, retrieved context | Correct abstention can still hide missed eligible evidence. |
| Answer | Final response versus supplied evidence and explicit user history | A fluent answer or verifier pass is not ground truth. |
| Citations | Final cited ID and the exact claim supported by that item | An existing ID may support a different fact; do not just match brackets. |
| Execution | Status, recovery path and whether the operation completed | An intermediate vector error with successful keyword fallback is not a failed request. |
| Memory writes | Candidate quote, approval, before/after version, stored state | Candidate extraction, approval and persistence are different transitions. |
| Isolation | Workspace ownership of context and storage | Foreign data entering context is a leak even if a later guard hides the answer. |

## Follow the entire response path

Separate raw generation from what the user finally received. Citation and
entailment guards can replace a bad draft with an abstention. Grade the final
response for answer/citation defects, while still checking independent retrieval
or isolation defects. HTTP 200 and the historical test's pass flag prove neither
grounding nor correct state changes.

Grounded mode with no evidence and no history abstains before generation.
With evidence, a model-based entailment check is fallible. General mode does not
require retrieved support for standard common knowledge. User statements in
conversation history can support an answer; previous assistant assertions cannot.

## Follow memory state transitions

Pending proposals should not change stored memories. Approval must use the expected
previous version; a stale proposal must not overwrite a newer user edit. A proposed
quote must occur verbatim in the current user message. A fabricated quote is an
extraction defect even before approval. Exact duplicate memories must be rejected.
Undo requires the current value to equal the revision's after value. Explicit
deletion removes the memory and associated proposals/revisions; verify stored state.

Memory deletion and disabling recall do not erase conversation history. Explicit
facts in retained user messages can still justify answers. Do not diagnose a
deleted-memory recall leak merely because the fact reappears from user history.

## Distinguish completed failures from missing observations

A missing relevant fact from completed retrieval is different from a timeout before
retrieval completes. Likewise, an unlogged evidence payload is not proof that no
evidence existed. Use insufficient evidence only if no definite finding is possible.
Do not invent causes such as a broken vector database when the trace shows only a miss.

When the applicable policy requires clarification between two project-specific
facts, selecting one without clarification is an answer defect even if its citation
supports that selected fact. Follow the requested label definitions; do not add
citation errors merely because the broader answer failed disambiguation.