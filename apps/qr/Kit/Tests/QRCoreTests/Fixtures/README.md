# Fixtures

One JSON array per area (SPEC §16). Every `*.json` here runs in two places: the Rust core's
`apps/qr/core/tests/analyzer.rs` (`every_fixture_passes`, checked case by case) and, through the
Swift façade, `everyFixtureRunsThroughTheSwiftAPI` in `../FacadeTests.swift`.
Peek fixtures are pairs in `peek/` and `peekreport/`: `<name>.html` plus `<name>.json`, run by
`apps/qr/core/tests/peek.rs` (`pair_matches_expectation`).
These double as the Android port's acceptance tests.

A case may carry `"known": ["example.com", …]`: registrable domains the person told us they know
(SPEC §4.4). The runner analyzes that case with `Analyzer(data:known:)`.
