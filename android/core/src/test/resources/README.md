# Swift interoperability fixture

`swift-schema1.json` was exported by the actual Swift `FastCore.ArchiveCodec.encode` implementation on 2026-11-13. It contains a fast with a fractional millisecond timestamp, linked journal, water, plan, rest day, custom timer and water preferences. All records are synthetic.

`InteropTest` decodes it, checks fields and emits `build/interop/kotlin-schema1.json`. That Kotlin output was also decoded and validated by Swift FastCore. The schema uses the same case-sensitive field names, UUID strings, canonical units and millisecond-since-1970 timestamps on both platforms. This is a record-transfer fixture, never a purchase fixture.
