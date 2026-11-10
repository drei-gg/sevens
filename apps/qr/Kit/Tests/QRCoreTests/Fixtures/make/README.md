# Make fixtures

`link-compaction.json`: one array of cases for `LinkCompactor` (SPEC §8). Not analyzer cases; the
top-level `Fixtures/*.json` files are the analyzer's.

```json
{ "id": "make-idn-host", "note": "why this case exists",
  "input": "https://Bücher.example/Path?Q=1",
  "trimmed": "https://Bücher.example/Path?Q=1",
  "compacted": "HTTPS://XN--BCHER-KVA.EXAMPLE/Path?Q=1",
  "gains": true }
```

- `input`: the link as typed.
- `trimmed`: `LinkCompactor.trimmed(input)` (default port and a lone trailing `/` removed).
- `compacted`: `LinkCompactor.compacted(input)`; null when the text is not an http(s) link.
- `gains`: capitalizing is expected to make the symbol smaller at the same level (informational;
  the app measures the real symbol). false for links where the path, query or fragment already
  keeps the code in byte mode.

Every case with a non-null `compacted` must parse to the same `LinkInfo.normalized` as `input`.
Ids are unique across all fixtures and start with `make-`.
