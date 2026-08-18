# mcp-revalidation

## Symptom
Alert `mcp-revalidation` is firing.

## Diagnosis (queries to run)
- Check the source metric in Prometheus.
- Confirm tenant, region, and recent deploys.

## Mitigation (immediate)
- Follow the module runbook in the alert annotations.
- If user-facing, enable graceful degradation.

## Rollback
- Revert the most recent related deploy if the alert started after a release.

## Follow-up
- File a post-incident note and tune the threshold if this was a false positive.
