# Synanton Guides

Synanton is a multi-tenant enterprise knowledge platform: it ingests documents from wherever an organization keeps them, and makes that content searchable — by keyword, by meaning, and by relationship — while making sure nobody sees more than they're entitled to.

The guides on this site explain **what the platform does and why it's built this way**, in plain language, for readers who want to understand Synanton without reading code, protobuf schemas, or gRPC contracts. If you need that level of detail, every guide ends with a "Go Deeper" table pointing to the normative engineering documents that are the actual source of truth.

## Find your guide

<div class="grid cards" markdown>

- :material-account-group: **For Everyone**

    How search and ingestion actually work, in terms anyone evaluating or using the platform can follow.

    [Search 101](search-101.md) · [Ingestion 101](ingestion-101.md)

- :material-shield-lock: **For Security & Compliance Teams**

    How Synanton decides what's sensitive, how it protects it, and who can prove that to an auditor.

    [Security 101](security-101.md)

- :material-server-network: **For Operators**

    How the platform is deployed and scaled, and what to do when something goes wrong.

    [Deployment 101](deployment-101.md) · [Troubleshooting 101](troubleshooting-101.md)

- :material-code-braces: **For Developers**

    API contracts, module implementation, and protobuf/gRPC schemas live in the engineering repository, not here.

    [Technical Reference on GitHub](https://github.com/synanton/platform/tree/main/docs/architecture){:target="_blank"}

</div>

## Questions these guides answer

- **How does Synanton protect sensitive data?** → [Security 101](security-101.md)
- **Why are chunks important, and why not just split on token count?** → [Ingestion 101](ingestion-101.md)
- **What happens when I search?** → [Search 101](search-101.md)
- **How do I deploy this, and what changes between deployment modes?** → [Deployment 101](deployment-101.md)
- **Something looks wrong — where do I even start?** → [Troubleshooting 101](troubleshooting-101.md)

## What you won't find here

By design, these guides don't cover API/gRPC contracts, SDK usage, module-level implementation, or internal incident-response runbooks. Those live in the engineering repository under `docs/architecture/`, `docs/implementation/`, `docs/api/`, and `docs/operations/runbooks/` — each guide links to the specific sections it draws from.

---

*This site tracks the platform's design revisions. Use the version selector to switch between guide sets aligned to a specific design baseline (`v1.22`, `v1.23`).*
