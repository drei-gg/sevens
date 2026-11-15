# Fixtures

One JSON array per area (SPEC §16); `FixtureRunnerTests` loads every `*.json` here.
Peek fixtures are pairs in `peek/`: `<name>.html` plus `<name>.json`.
These double as the Android port's acceptance tests.

A case may carry `"known": ["example.com", …]`: registrable domains the person told us they know
(SPEC §4.4). The runner analyzes that case with `Analyzer(data:known:)`.
