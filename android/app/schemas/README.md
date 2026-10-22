# Room schemas

19.json is exported from the unchanged schema19 sources with Room KSP2.7.0.
20.json is exported from the release build after migration changes.

18.json is a **reconstructed structural fixture**, not a historical compiler export.
Migration18→19 adds only download_queue; the fixture copies the exported19
entities except that table, preserving indexes and FTS options. Its identity hash
is a deterministic fixture hash, not a historical Room identity hash.
MigrationTestHelper uses it to create18 and validates the result against real20.
The nullable17 compatibility test starts from18 and changes user_preferences
and user_version to model the known historical17 variants, including an external-content FTS index. It does not claim
coverage of every historical APK or a historically exported17 schema.

Device migration tests are authored but were not run: the owner explicitly
excluded tests, lint and benchmarks from this implementation session.
