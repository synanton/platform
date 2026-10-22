# Synanton Platform EN–RU glossary (baseline)

This is a starter glossary, not a substitute for terminology already defined by Synanton's canonical designs. Before applying a term, check its local definition and use across related documents. Add entries when decisions are reusable or architecture-sensitive.

| English | Russian default | Usage note |
|---|---|---|
| authority | полномочия; источник полномочий | Choose by context: granted authority vs canonical source of truth. |
| source of truth | источник истины; канонический источник | Prefer the project's established phrase; avoid using it as a vague synonym for authority. |
| boundary | граница | Specify trust, responsibility, or system boundary when context requires it. |
| trust boundary | граница доверия | Do not conflate with a network boundary. |
| identity | идентичность; учётная запись / субъект | Use «идентичность» for the abstract identity concept; use project-defined terms for principals and accounts. |
| authentication | аутентификация | Establishing or verifying identity. |
| authorization | авторизация | Determining whether an action is permitted. |
| access control | контроль доступа | Do not automatically substitute for authorization if the source distinguishes them. |
| classification | классификация | Classification does not itself imply permission. |
| policy | политика | Use a project-defined name if it refers to a specific policy object or subsystem. |
| principal | субъект доступа | Use the project's established term if one exists. |
| permission | разрешение; право | Distinguish an allowed action from the mechanism that evaluates it. |
| enforcement | принудительное применение; обеспечение соблюдения | Choose based on whether the text describes technical enforcement or governance. |
| invariant | инвариант | A property that must remain true. |
| contract | контракт | For a technical interface or behavioral guarantee; not necessarily a legal contract. |
| requirement | требование | Preserve whether it is mandatory, recommended, or optional. |
| constraint | ограничение | Do not conflate with a requirement. |
| capability | возможность; поддерживаемая функция | Disambiguate ability/capability from permission. |
| control plane | плоскость управления | Keep distinct from data plane. |
| data plane | плоскость данных | Use consistently with «плоскость управления». |
| component | компонент | Preserve canonical component names. |
| subsystem | подсистема | Prefer when the source treats it as a bounded architectural unit. |
| ownership | владение; ответственность за компонент | Choose based on resource ownership vs accountable responsibility. |
| delegation | делегирование | Preserve who delegates to whom and the scope. |
| canonical | канонический | Indicates designated authoritative form, not merely common or preferred. |
| derived data | производные данные | Preserve the relationship to the source data. |
| source document | исходный документ | In translation workflow, distinguish from the translated edition. |
| normative | нормативный | For statements that establish requirements or permitted behavior. |
| MUST | ДОЛЖЕН / MUST | Follow the repository's explicit normative-keyword convention; do not introduce a new convention casually. |
| MUST NOT | НЕ ДОЛЖЕН / MUST NOT | Preserve prohibition strength. |
| SHOULD | СЛЕДУЕТ / SHOULD | Preserve recommendation strength, not an absolute requirement. |
| MAY (permission) | МОЖЕТ / MAY | Permission, not capability; follow the repository convention. |
| can (capability) | может; способен | Do not translate as permission unless context says so. |
| reliability | надёжность | Use the repository's chosen spelling convention consistently. |
| availability | доступность | Do not equate with reliability. |
| fault | сбой | A component or subsystem deviates from expected behavior; verify the source's definition. |
| failure | отказ | A service or component fails to provide the required behavior; preserve source-specific distinctions. |
| fault tolerance | отказоустойчивость; устойчивость к сбоям | Choose by context and project convention. |
| consistency (distributed systems) | согласованность | Do not conflate with correctness or integrity. |
| correctness | корректность | A property of behavior/results according to a specification. |
| integrity | целостность | Data integrity or security integrity, depending on context. |
| replication | репликация | Keep distinct from partitioning/sharding. |
| partitioning | секционирование; разделение на разделы | Use «шардинг» only where the source or product vocabulary specifically means sharding. |
| throughput | пропускная способность | Use a more specific metric if the source defines operations or bytes per unit time. |
| latency | задержка | Use «время отклика» only when that is the actual metric. |
| timeout | тайм-аут; время ожидания | A timeout is not proof that a remote process has failed. |
| durability | долговечность; сохранность данных | Choose according to the storage guarantee being described. |
| batch processing | пакетная обработка | — |
| stream processing | потоковая обработка | — |
| schema | схема | Preserve schema identifiers and field names. |
| serialization | сериализация | — |
| runbook | инструкция по эксплуатации; runbook | Prefer the existing project term. |
| design document | документ проектирования; design | Keep design IDs unchanged. |
| ADR (Architecture Decision Record) | запись об архитектурном решении (ADR) | Retain ADR identifier and filename. |
| source/target pair | пара исходного документа и перевода | Use for language-edition tracking. |
| stale translation | устаревший перевод | A translation that no longer matches its source revision. |

## Glossary maintenance

For a new term, add:
1. English term;
2. preferred Russian equivalent;
3. context / disambiguation;
4. source document or canonical definition when known;
5. rejected alternatives only when the distinction matters.

Do not mark a term as canonically approved unless a Synanton source or maintainer decision supports that status.
