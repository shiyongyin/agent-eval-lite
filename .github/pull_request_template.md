## Summary

Describe what changed and why.

## Validation

- [ ] `mvn -q test`
- [ ] `mvn -q package`
- [ ] `bin/agent-eval suite --tasks-root tasks --fail-on-not-passed`
- [ ] `bash bin/ci-smoke.sh` when touching judge/tool/trace/tasks/redteam/CI

## Safety Notes

- [ ] No `hidden/expected` answers, private judge details, real credentials, run artifacts, or customer data are exposed.
- [ ] `docs/CODEMAP.md` was regenerated if classes, CLI commands, checks, trace events, or tasks changed.
