## Summary

Short description of the change.

## Linked issue

<!-- Required only when the branch references an issue number. -->
Closes #<issue-number>

## Type of change

- [ ] `feat` (new feature)
- [ ] `bug` (bug fix)
- [ ] `task` (issue-driven task)
- [ ] `refactor` (code refactoring)
- [ ] `chore` (maintenance, dependencies)
- [ ] `docs` (documentation updates)
- [ ] `ci` (CI/CD workflows)
- [ ] `test` (test additions or refactoring)
- [ ] `perf` (performance improvements)

## Checklist (Definition of Done)

- [ ] Code compiles and tests pass locally: `mise run test` or `sbt 'testOnly *'`
- [ ] `scalafmt` applied (`mise run format` or `sbt scalafmtAll`)
- [ ] Tests/benchmarks added (if applicable)
- [ ] Documentation updated (if required)
- [ ] Branch name follows `<type>/<short-desc>` (type: task|feat|bug|refactor|chore|docs|ci|test|perf; add `<id>-` to link an issue)

Please do not merge into `main` without passing required CI checks and code review.
