## Validation

- [ ] If this PR changes `evaluation/dev25/gold/**` or `evaluation/dev25/manifest.tsv` source mappings, attach a `python3 evaluation/dev25/validate.py` result below.

## Container lifecycle (required for PRs touching test/dev fixtures)

- [ ] New/changed containers use `--rm` or `restart: "no"`
- [ ] `ogce.dev=true` and invocation-scoped `ogce.run` labels applied
- [ ] Ephemeral host ports (no fixed well-known port binding)
- [ ] Teardown runs on success and failure
- [ ] Verified with `docker ps -a --filter label=ogce.dev=true` returning empty after the test
