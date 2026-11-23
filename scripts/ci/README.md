# CI scripts

## Docker leak check

- **Workflow/job:** `Docker Leak Check` → `Run-scoped Docker leak detection` (`.github/workflows/docker-leak-check.yml`).
- **Local synthetic test:** `./scripts/ci/test-check-docker-leaks.sh` (creates a labeled container/network/volume, proves detection, proves an unlabeled resource is ignored, and removes only its own resources).
- **Verify a run/project:** `./scripts/ci/check-docker-leaks.sh --run <id> [--compose-project <name>] [--types containers,networks,volumes]`.
- The checker is read-only: it reports resources and exits non-zero; it never prunes, deletes, or stops containers.
- `Local Real Demo v1` runs the checker after teardown for its Compose project and run label.
