# Synanton Platform English–Russian glossary

This glossary is the canonical terminology source for the Russian design mirror. Keep the Russian rendering fixed for each English term. `yes` means preserve the English term exactly; `no` means translate it using the listed Russian rendering.

| English term | Russian rendering | do-not-translate flag |
|---|---|---|
| `Operation` | `Operation` | yes |
| `SourceIdentity` | `SourceIdentity` | yes |
| `SourceVersion` | `SourceVersion` | yes |
| `IngestionRun` | `IngestionRun` | yes |
| `ManifestEntry` | `ManifestEntry` | yes |
| `SecurityContext` | `SecurityContext` | yes |
| `ValidatedIdentity` | `ValidatedIdentity` | yes |
| `ToolResult` | `ToolResult` | yes |
| `ContentStore` | `ContentStore` | yes |
| `ContentPullPort` | `ContentPullPort` | yes |
| `ACCEPTED` | `ACCEPTED` | yes |
| `QUEUED` | `QUEUED` | yes |
| `RUNNING` | `RUNNING` | yes |
| `SUCCEEDED` | `SUCCEEDED` | yes |
| `FAILED` | `FAILED` | yes |
| `CANCEL_REQUESTED` | `CANCEL_REQUESTED` | yes |
| `CANCELLED` | `CANCELLED` | yes |
| `EXPIRED` | `EXPIRED` | yes |
| `RETRYABLE` | `RETRYABLE` | yes |
| `WAITING` | `WAITING` | yes |
| `ESCALATED` | `ESCALATED` | yes |
| `REVERSIBLE` | `REVERSIBLE` | yes |
| `COMPENSATABLE` | `COMPENSATABLE` | yes |
| `INVALIDATABLE` | `INVALIDATABLE` | yes |
| `IRREVERSIBLE` | `IRREVERSIBLE` | yes |
| `FEATURE_APPLIED` | `FEATURE_APPLIED` | yes |
| `FEATURE_UNSUPPORTED` | `FEATURE_UNSUPPORTED` | yes |
| `FEATURE_NOT_APPLICABLE` | `FEATURE_NOT_APPLICABLE` | yes |
| `FULL` | `FULL` | yes |
| `PARTIAL` | `PARTIAL` | yes |
| `REPLAY_SAFE` | `REPLAY_SAFE` | yes |
| `REPLAY_WITH_POLICY` | `REPLAY_WITH_POLICY` | yes |
| `NOT_REPLAYABLE` | `NOT_REPLAYABLE` | yes |
| `VALIDATION_ERROR` | `VALIDATION_ERROR` | yes |
| `AUTHENTICATION_REQUIRED` | `AUTHENTICATION_REQUIRED` | yes |
| `AUTHORIZATION_DENIED` | `AUTHORIZATION_DENIED` | yes |
| `RESOURCE_NOT_FOUND` | `RESOURCE_NOT_FOUND` | yes |
| `RESOURCE_CONFLICT` | `RESOURCE_CONFLICT` | yes |
| `PRECONDITION_FAILED` | `PRECONDITION_FAILED` | yes |
| `RATE_LIMITED` | `RATE_LIMITED` | yes |
| `DEPENDENCY_UNAVAILABLE` | `DEPENDENCY_UNAVAILABLE` | yes |
| `OPERATION_FAILED` | `OPERATION_FAILED` | yes |
| `INTERNAL_ERROR` | `INTERNAL_ERROR` | yes |
| `TRANSIENT` | `TRANSIENT` | yes |
| `PERMANENT` | `PERMANENT` | yes |
| `SECURITY` | `SECURITY` | yes |
| `INVALID` | `INVALID` | yes |
| `DEPENDENCY` | `DEPENDENCY` | yes |
| `TIMEOUT` | `TIMEOUT` | yes |
| `RESOURCE` | `RESOURCE` | yes |
| `acl_grants` | `acl_grants` | yes |
| `resource_acl` | `resource_acl` | yes |
| `class_grants` | `class_grants` | yes |
| `classification[]` | `classification[]` | yes |
| `store_original` | `store_original` | yes |
| `fail_closed` | `fail_closed` | yes |
| `AclInjector` | `AclInjector` | yes |
| `ClassificationDetector` | `ClassificationDetector` | yes |
| `ReindexAfterPolicyChangeWorkflow` | `ReindexAfterPolicyChangeWorkflow` | yes |
| `content_original` | `content_original` | yes |
| `content_masked` | `content_masked` | yes |
| `embedding_original` | `embedding_original` | yes |
| `embedding_masked` | `embedding_masked` | yes |
| `acl_mask` | `acl_mask` | yes |
| `representation=original` | `representation=original` | yes |
| `representation=masked` | `representation=masked` | yes |
| `HIGH_SECURITY` | `HIGH_SECURITY` | yes |
| `STANDARD` | `STANDARD` | yes |
| `RESTRICTED` | `RESTRICTED` | yes |
| `PERSONAL` | `PERSONAL` | yes |
| `FINANCIAL` | `FINANCIAL` | yes |
| `PUBLIC` | `PUBLIC` | yes |
| `USER` | `USER` | yes |
| `GROUP` | `GROUP` | yes |
| `ROLE` | `ROLE` | yes |
| `QUARANTINED` | `QUARANTINED` | yes |
| `PENDING_REVIEW` | `PENDING_REVIEW` | yes |
| `SINGLE` | `SINGLE` | yes |
| `DUAL` | `DUAL` | yes |
| `MASKED‑ONLY` | `MASKED‑ONLY` | yes |
| `Single` | `Single` | yes |
| `Dual` | `Dual` | yes |
| `Masked-only` | `Masked-only` | yes |
| `Masked‑only` | `Masked‑only` | yes |
| `MASK` | `MASK` | yes |
| `DROP` | `DROP` | yes |
| `QUARANTINE` | `QUARANTINE` | yes |
| `REDACTED:CLASS` | `REDACTED:CLASS` | yes |
| `SSN` | `SSN` | yes |
| `PII` | `PII` | yes |
| `security_officer` | `security_officer` | yes |
| `workflow_states` | `workflow_states` | yes |
| `workflow_steps` | `workflow_steps` | yes |
| `workflow_leases` | `workflow_leases` | yes |
| `outbox_events` | `outbox_events` | yes |
| `inbox_events` | `inbox_events` | yes |
| `processed_events` | `processed_events` | yes |
| `processed_source_versions` | `processed_source_versions` | yes |
| `chunk_source_versions` | `chunk_source_versions` | yes |
| `tenant_registry` | `tenant_registry` | yes |
| Proposed | Предложено | no |
| Approved | Утверждено | no |
| Approved (design) | Утверждено (дизайн) | no |
| Approved (architecture) | Утверждено (архитектура) | no |
| Approved (contract/readiness review only) | Утверждено (только проверка контракта и готовности) | no |
| Contract frozen | Контракт зафиксирован | no |
| In progress | В работе | no |
| Implemented | Реализовано | no |
| Not started | Не начато | no |
| Partial | Частично | no |
| Validated (evidence/date) | Подтверждено (доказательство/дата) | no |
| Superseded | Заменено более новой версией | no |
| authentication | аутентификация | no |
| authorization | авторизация | no |
| classification | классификация | no |
| masking | маскирование | no |
| fail-closed | безопасный отказ при сбое | no |
| fail‑closed | безопасный отказ при сбое | no |
| representation | представление | no |
| least privilege | принцип наименьших привилегий | no |
| resource ACL | ACL ресурса | no |
| redaction | редактирование чувствительных данных | no |
| quarantine | карантин | no |
| tenant scope | область действия арендатора | no |
| unlabelled content | контент без классификационной метки | no |
| security context | контекст безопасности | no |
| classification-aware search | поиск с учётом классификации | no |
| MUST | ДОЛЖЕН | no |
| MUST NOT | НЕ ДОЛЖЕН | no |
| SHOULD | СЛЕДУЕТ | no |
| MAY | МОЖЕТ | no |
| envelope | конверт события | no |
| outbox | исходящая очередь (outbox) | no |
| inbox | входящая очередь (inbox) | no |
| tombstone | маркер удаления (tombstone) | no |
| chunk | фрагмент | no |
| provenance | происхождение данных | no |
| idempotency | идемпотентность | no |
| lease | аренда (lease) | no |
| reconciliation | сверка | no |
