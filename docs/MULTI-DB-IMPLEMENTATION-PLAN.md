# Multi-Database Support — Implementation Plan

**Target repo:** `opentmf-db-lock-service`
**Target version:** the next minor (suggested `2.2.0`, or a new SNAPSHOT above current `2.1.1-SNAPSHOT`)
**Written:** 2026-04-23

---

## 1. Why

Today the library auto-creates tables only for PostgreSQL. The runtime lock/unlock
queries in `DbLockServiceImpl` are already SQL-92 compliant (verified — plain
`select count`, `insert`, `insert … select`, `update … from`, `delete`), so the
*only* PostgreSQL-specific thing in this repo is the DDL in
`src/main/resources/db/creation_script.sql` (uses `SERIAL`, `TIMESTAMP WITH TIME ZONE`,
`COMMENT ON`, and `ALTER … TYPE` syntax).

Downstream consumers (`integration-adapter-generator-suite` and its generated
adapters) want to support customers deploying against Oracle, MySQL, MSSQL, etc.,
without forking or hand-porting the DDL. Pushing that logic into this library is
the right layer: one port, many consumers.

Unique feature to preserve: the `DB_LOCK_LATEST` table plus the
`@UsingClusterLock(requestedVersion=…)` version-gated skip. This is **not** what
ShedLock does, so customers can't trivially swap libraries — hence the investment
is justified.

---

## 2. Scope

### In scope (first pass)

- Ship ready-to-run DDL for **PostgreSQL, MySQL/MariaDB, Oracle, Microsoft SQL
  Server, IBM DB2, H2**.
- Auto-detect the active dialect from the JDBC connection metadata.
- Allow an explicit override via a new property `opentmf.db-lock.dialect`.
- Run the matching DDL when `opentmf.db-lock.create-tables=true` (unchanged default).
- Testcontainers-backed integration tests for each dialect.
- Docs + CHANGELOG + bumped version.

### Out of scope (explicitly)

- Sybase, Firebird, Informix, SAP HANA, CockroachDB-specific quirks, Amazon
  Redshift, Google Spanner. Community can contribute later; customers can always
  set `create-tables=false` and supply their own DDL.
- Schema migration between library versions. Today there's only one lock-table
  schema. If that changes in the future, a migration strategy becomes its own
  design discussion (probably Liquibase/Flyway changesets shipped in the jar).
- Dialect-specific lock-algorithm optimisations. The queries stay portable.

---

## 3. Current state — what to preserve

Code locations the plan will touch, for orientation:

| File | Role today | After this work |
|---|---|---|
| `src/main/resources/db/creation_script.sql` | Only DDL, PG-flavoured | Renamed to `postgresql.sql`, kept PG-specific |
| `src/main/java/.../util/JdbcHelper.java:114-124` (`createTables`) | Hardcodes `db/creation_script.sql` | Takes a resolved `ClassPathResource` (or path string) |
| `src/main/java/.../config/DbLockAutoConfiguration.java:41-49` | Calls `JdbcHelper.createTables(jdbcTemplate)` | Resolves dialect first, then calls the DDL-aware overload |
| `src/main/java/.../config/DbLockProperties.java` | `createTables: boolean` | Adds `dialect` (optional `Dialect` enum) |
| `src/test/java/.../DbLockApplicationIT.java` + friends | PG-only Testcontainers | Parameterised across dialects |

All public API (annotations, `DbLockService` interface, exception types, model
records) stays unchanged. Only additions.

---

## 4. Design

### 4.1 New enum `Dialect`

`src/main/java/org/opentmf/db/lock/dialect/Dialect.java`

```java
package org.opentmf.db.lock.dialect;

public enum Dialect {
  POSTGRESQL("postgresql", "db/postgresql.sql"),
  MYSQL("mysql", "db/mysql.sql"),
  ORACLE("oracle", "db/oracle.sql"),
  SQLSERVER("sqlserver", "db/sqlserver.sql"),
  DB2("db2", "db/db2.sql"),
  H2("h2", "db/h2.sql");

  private final String id;              // lowercase, matches config value
  private final String ddlResourcePath; // classpath location of DDL script
  // getters + static fromId(String) with a clear error listing supported ids
}
```

Keep the enum small and closed. If someone asks for DB2 later, adding an entry is
a 3-line change.

### 4.2 Auto-detection

`src/main/java/org/opentmf/db/lock/dialect/DialectDetector.java`

```java
public final class DialectDetector {

  public static Dialect detect(DataSource dataSource) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      String product = conn.getMetaData().getDatabaseProductName();
      return mapProductName(product)
          .orElseThrow(() -> new IllegalStateException(
              "Unsupported database product: '" + product + "'. "
              + "Supported: " + Arrays.toString(Dialect.values())
              + ". Set opentmf.db-lock.dialect to override."));
    }
  }

  static Optional<Dialect> mapProductName(String product) {
    if (product == null) return Optional.empty();
    String p = product.toLowerCase(Locale.ROOT);
    if (p.contains("postgresql"))               return Optional.of(Dialect.POSTGRESQL);
    if (p.contains("mysql") || p.contains("mariadb")) return Optional.of(Dialect.MYSQL);
    if (p.contains("oracle"))                   return Optional.of(Dialect.ORACLE);
    if (p.contains("sql server") || p.contains("microsoft"))
                                                return Optional.of(Dialect.SQLSERVER);
    if (p.contains("db2"))                      return Optional.of(Dialect.DB2);
    if (p.contains("h2"))                       return Optional.of(Dialect.H2);
    return Optional.empty();
  }
}
```

`mapProductName` is package-private + pure, so it's trivially unit-testable
without a real DataSource.

### 4.3 Config property

Update `DbLockProperties`:

```java
/**
 * Optional explicit dialect. When unset, the dialect is auto-detected from the
 * JDBC connection metadata. Set this when auto-detection picks the wrong answer
 * (e.g. drivers that identify themselves ambiguously) or when you want to pin
 * the value for deterministic startup.
 */
private Dialect dialect;
```

Also update the javadoc on `createTables` — the comment currently says *"the db
lock service supports creating tables only for PostgreSQL"* (`DbLockProperties.java:29`).
Replace that paragraph with a list of supported dialects.

### 4.4 Dispatch in autoconfig

`DbLockAutoConfiguration.dbLockService()` becomes:

```java
@Bean
public DbLockService dbLockService() throws SQLException {
  if (dbLockProperties.isCreateTables()) {
    Dialect dialect = dbLockProperties.getDialect() != null
        ? dbLockProperties.getDialect()
        : DialectDetector.detect(jdbcTemplate.getDataSource());
    log.info("Applying lock-table DDL for dialect={}", dialect.getId());
    JdbcHelper.createTables(jdbcTemplate, dialect);
  }
  var dbLockService = new DbLockServiceImpl(jdbcTemplate, dbLockProperties);
  dbLockService.removeStaleLocks();
  return dbLockService;
}
```

`JdbcHelper.createTables` gains a `(JdbcTemplate, Dialect)` overload that loads
`dialect.getDdlResourcePath()` instead of the hardcoded path. Keep the old
one-arg method for backward compat and have it delegate to
`createTables(jdbcTemplate, Dialect.POSTGRESQL)` (deprecated, for any external
caller). The library's own code paths move off it.

### 4.5 DDL files

Six files under `src/main/resources/db/`:

- `postgresql.sql` — rename from `creation_script.sql`. Collapse the existing
  `ALTER … TYPE VARCHAR(50)` statements into the initial `CREATE TABLE` columns
  (cleaner, still PG-only).
- `mysql.sql`
- `oracle.sql`
- `sqlserver.sql`
- `db2.sql`
- `h2.sql`

Schema constraints each file must satisfy:

- **Table `DB_LOCK`**: `id` auto-increment PK, `lock_type CHAR(1)` with `CHECK` IN
  (`'B','C','X','Y','Z'`), `lock_version VARCHAR(50)`, `hostname VARCHAR(255)`,
  `created_on` timestamp-with-tz, default = current timestamp. Unique index on
  `lock_type`.
- **Table `DB_LOCK_HISTORY`**: same columns + `lock_id INT`, `lock_acquired_on`,
  `lock_released_on`. Indexes on `lock_acquired_on` and on `(lock_type,
  lock_version)`.
- **Table `DB_LOCK_LATEST`**: `lock_type CHAR(1)` (PK), same CHECK constraint,
  `lock_version VARCHAR(50)`, `hostname VARCHAR(255)`, `lock_acquired_on`.

Per-dialect notes — what to watch for:

| Dialect | Auto-increment | Timestamp w/ tz | CHECK | IF NOT EXISTS | Idempotency |
|---|---|---|---|---|---|
| PostgreSQL | `SERIAL` | `TIMESTAMP WITH TIME ZONE` | native | native | native |
| MySQL 8 | `INT AUTO_INCREMENT` | `TIMESTAMP` (no tz — use UTC at app layer) | 8.0.16+ enforced | native | `CREATE TABLE IF NOT EXISTS` |
| Oracle 12c+ | `GENERATED BY DEFAULT AS IDENTITY` | `TIMESTAMP WITH TIME ZONE` | native | ❌ | Wrap in `BEGIN … EXCEPTION WHEN OTHERS THEN NULL; END;` PL/SQL block |
| SQL Server | `INT IDENTITY(1,1)` | `DATETIMEOFFSET` | native | ❌ pre-2016; use `IF OBJECT_ID(…) IS NULL` | Guard each statement |
| DB2 LUW 11.x | `GENERATED BY DEFAULT AS IDENTITY` | `TIMESTAMP` with `WITH TIME ZONE` (LUW 11.5+) | native | ❌ | Wrap in `BEGIN … EXCEPTION WHEN SQLSTATE '42710' THEN NULL; END;` compound SQL block; catches "object already exists" |
| H2 | `IDENTITY` or `GENERATED BY DEFAULT AS IDENTITY` | `TIMESTAMP WITH TIME ZONE` | native | native | native |

Two things to be explicit about in the DDL files:

1. **Idempotency style varies by dialect.** Pick the native "if not exists" where
   it's available; use the dialect's guard pattern otherwise. Each file should
   run cleanly twice.
2. **Timestamp semantics.** MySQL has no `TIMESTAMP WITH TIME ZONE`. Document in
   `mysql.sql` (as a leading comment) that hosts running the adapter must use
   UTC, or the `created_on`/`lock_acquired_on` values will be wrong. This is
   already an implicit assumption; just call it out.

### 4.6 `ScriptUtils` and Oracle PL/SQL blocks

Spring's `ScriptUtils.executeSqlScript` splits on `;` by default, which breaks
Oracle's `BEGIN … END;` blocks. The fix:

- Use the overload
  `ScriptUtils.executeSqlScript(Connection, EncodedResource, boolean continueOnError, boolean ignoreFailedDrops, String commentPrefix, String separator, String blockCommentStartDelimiter, String blockCommentEndDelimiter)`
- For Oracle (and MSSQL if you end up using multi-statement batches), pass
  `separator="/"` and place a line with just `/` between PL/SQL blocks —
  this is Oracle's SQL*Plus-style terminator and is idiomatic for `.sql` files.
- Simpler alternative: split Oracle PL/SQL into *single-statement* idempotency
  checks and avoid multi-statement batches entirely. One `EXECUTE IMMEDIATE`
  per CREATE. This avoids needing custom separators. Recommended.

Encode the separator choice as a property on `Dialect` if it differs per dialect,
or just keep it uniform by avoiding Oracle blocks entirely (preferred). Less
configuration surface.

### 4.7 Fallback to user-supplied DDL

Add one more escape hatch: `opentmf.db-lock.ddl-location` (optional `String`).
When set, it overrides everything — `createTables` loads from that classpath or
filesystem path verbatim. Useful for customers on unsupported dialects who still
want `createTables: true` behaviour using their own file.

Resolution order (first match wins):
1. `opentmf.db-lock.ddl-location` (user-supplied explicit path)
2. `opentmf.db-lock.dialect` (user-supplied enum; pick its bundled file)
3. Auto-detect via `DialectDetector`

---

## 5. Testing

### 5.1 What to test

- `Dialect.fromId` for each id + one unknown id (exception).
- `DialectDetector.mapProductName` for each of: `"PostgreSQL 16.0"`, `"MySQL"`,
  `"MariaDB"`, `"Oracle"`, `"Microsoft SQL Server"`, `"DB2/LINUXX8664"`, `"H2"`,
  `"CockroachDB"` (expect empty), `null` (expect empty).
- DDL files: one `@ParameterizedTest` driven by Testcontainers, one container
  per dialect. Each test:
  1. Spin up a container.
  2. Build a `DataSource` pointing at it.
  3. Call `JdbcHelper.createTables(jdbcTemplate, dialect)` twice — **second call
     must not throw** (idempotency).
  4. Call `DbLockServiceImpl.acquireLock` / `releaseLock` through a small
     scenario and assert the table state (row counts in each of the three
     tables).

### 5.2 Container images to use

| Dialect | Image | Notes |
|---|---|---|
| PostgreSQL | `postgres:16` (existing) | No change |
| MySQL | `mysql:8.4` | `testcontainers.mysql` |
| MariaDB | skip for v1; `MYSQL` DDL should cover both — add a nightly/manual job later if needed |
| Oracle | `gvenzl/oracle-free:23-slim-faststart` | Oracle-sanctioned, no licence cost for CI use. Slow to boot (~45s) — keep tests tight |
| MSSQL | `mcr.microsoft.com/mssql/server:2022-latest` | Needs `ACCEPT_EULA=Y` + a sufficiently strong SA password |
| DB2 | `icr.io/db2_community/db2:latest` | IBM Community Edition, no fees for CI. Slowest starter (2–3 min cold boot) — prefer the gated/heavy CI job; use `withReuse(true)` locally |
| H2 | in-memory (no container) | Fastest sanity check; does not replace the container-backed coverage |

Add Testcontainers `oracle-xe`, `mssqlserver`, `mysql`, `db2` modules as test
dependencies (mirroring the existing `org.testcontainers:postgresql`).

### 5.3 JaCoCo coverage

The repo enforces LINE/INSTR 80%, BRANCH 60%, CLASS 0 missed (`pom.xml:250-277`).
New classes (`Dialect`, `DialectDetector`) are small and trivially coverable.
Ensure `DialectDetector.mapProductName` has a test for every branch. The
parameterised dialect-container IT will keep `JdbcHelper.createTables` covered
for each file.

### 5.4 CI time budget

Oracle, MSSQL, and especially DB2 containers are slow. Two options:

1. Run all six in a single CI job (serial). Expect 5–7 min added, dominated by
   the DB2 cold start.
2. Split into a matrix: PG + H2 + MySQL in the default PR job (fast), Oracle +
   MSSQL + DB2 in a separate "heavy" job gated by a label or nightly. Keeps PR
   feedback tight.

Recommend #2 if your CI is GitHub Actions — matrix jobs are cheap; the DB2 and
Oracle container startup times are the bottleneck. For the initial PR, #1 is
simpler and acceptable if total CI time remains under ~10 min.

---

## 6. Docs

### 6.1 README changes (`README.md`)

Add a "Supported Databases" section listing the six dialects and the behaviour
of `dialect` + `ddl-location` + auto-detect. Include a table of "Which DB, what
to set":

| Your DB | Minimum config |
|---|---|
| PostgreSQL | nothing — auto-detected |
| MySQL / MariaDB | nothing — auto-detected |
| Oracle | nothing — auto-detected; for 12c earlier, set `dialect: oracle` explicitly |
| SQL Server | nothing — auto-detected |
| IBM DB2 LUW 11.5+ | nothing — auto-detected |
| H2 (dev/test) | nothing — auto-detected |
| Anything else | set `create-tables: false` and run the DDL yourself |

Call out the MySQL no-tz caveat. Also note the DB2 minimum version: the shipped
DDL targets DB2 LUW 11.5+ (for `TIMESTAMP WITH TIME ZONE`). Older DB2 versions
work with `create-tables: false` + hand-written DDL that uses plain `TIMESTAMP`.

### 6.2 CHANGELOG

Add under a new `[2.2.0]` section (or whatever version you settle on):

```markdown
## [2.2.0] - 2026-MM-DD

### Added
- **Multi-database DDL support.** The library now ships creation scripts for
  MySQL/MariaDB, Oracle, Microsoft SQL Server, IBM DB2, and H2 in addition to
  PostgreSQL. When `opentmf.db-lock.create-tables=true`, the active dialect is
  auto-detected from the JDBC connection metadata and the matching DDL runs.
  Runtime lock and unlock queries were already SQL-92 compliant, so no
  algorithm changes were needed.
- New property `opentmf.db-lock.dialect` — explicit override for auto-detection
  (useful when the driver identifies ambiguously, e.g. Aurora-PG clones).
- New property `opentmf.db-lock.ddl-location` — classpath or filesystem path to
  a user-supplied DDL file, taking precedence over both auto-detection and the
  `dialect` setting. For dialects the library does not ship out of the box.
- `Dialect` enum and `DialectDetector` utility published under
  `org.opentmf.db.lock.dialect`.

### Changed
- `src/main/resources/db/creation_script.sql` renamed to `db/postgresql.sql`;
  the column-widening `ALTER … TYPE` statements are collapsed into the initial
  `CREATE TABLE` definitions. Existing installs are unaffected (the widening
  already ran).
- Documentation in `DbLockProperties.createTables` no longer implies PostgreSQL
  is the only supported target.

### Deprecated
- `JdbcHelper.createTables(JdbcTemplate)` — use the new
  `createTables(JdbcTemplate, Dialect)` overload. The old one defaults to
  `Dialect.POSTGRESQL` for source compatibility and will be removed in a future
  major.
```

### 6.3 Javadocs

`DbLockProperties.dialect` and `ddlLocation` need full javadoc (the
configuration-processor emits them as metadata consumed by IDE auto-completion).

---

## 7. Step-by-step task list

Work these in order; each leaves the build green.

1. **Rename DDL + collapse `ALTER` statements**
   - `git mv src/main/resources/db/creation_script.sql src/main/resources/db/postgresql.sql`
   - Rewrite `CREATE TABLE` lines to use `VARCHAR(50)` directly; delete the
     three `ALTER TABLE … TYPE VARCHAR(50)` lines.
   - Update `JdbcHelper.createTables` to point at the new path.
   - Run the existing test suite; everything should stay green.

2. **Add `Dialect` enum + `DialectDetector`**
   - New package `org.opentmf.db.lock.dialect`.
   - Unit tests for `fromId` and `mapProductName` (every branch).

3. **Add config properties `dialect` and `ddlLocation`**
   - Update `DbLockProperties`.
   - Update the comment on `createTables`.
   - Regenerate `spring-configuration-metadata.json` (happens automatically via
     the configuration-processor).

4. **Wire dispatch in `DbLockAutoConfiguration`**
   - Resolve dialect (`ddlLocation` → `dialect` → `DialectDetector`).
   - Log the chosen dialect at INFO once at startup.
   - Pass a `ClassPathResource` (or `Resource`) into the new
     `JdbcHelper.createTables` overload.

5. **Write `mysql.sql`**
   - Smallest delta from PG. Use `INT AUTO_INCREMENT`, `TIMESTAMP` (document the
     UTC assumption in a leading comment), `CHECK` constraints, native
     `CREATE TABLE IF NOT EXISTS`.
   - Add a MySQL Testcontainers dependency.
   - Parameterise the IT to run against MySQL.

6. **Write `h2.sql`**
   - Easy: H2 supports PG-compatible mode; most of `postgresql.sql` works with
     minor tweaks (`SERIAL` → `IDENTITY`, `TIMESTAMP WITH TIME ZONE` → same in
     H2 2.x). In-memory test — no container.

7. **Write `sqlserver.sql`**
   - `INT IDENTITY(1,1)` for PKs, `DATETIMEOFFSET` for timestamps with tz,
     guard each CREATE with `IF OBJECT_ID(N'DB_LOCK', N'U') IS NULL BEGIN … END`.
   - Testcontainers module `mssqlserver`.

8. **Write `oracle.sql`**
   - `GENERATED BY DEFAULT AS IDENTITY`, `TIMESTAMP WITH TIME ZONE`, guard each
     statement in `BEGIN EXECUTE IMMEDIATE '…'; EXCEPTION WHEN OTHERS THEN … END;`.
   - Use a uniform separator strategy — either keep each statement on its own
     (no `/` separators) and pass `";"` to `ScriptUtils`, or introduce `/` for
     PL/SQL blocks and pass `"/"`. Recommend the former.
   - Testcontainers image `gvenzl/oracle-free:23-slim-faststart`.

9. **Write `db2.sql`**
   - `GENERATED BY DEFAULT AS IDENTITY`, `TIMESTAMP WITH TIME ZONE` (LUW 11.5+),
     guard each statement in DB2 compound SQL:
     `BEGIN EXECUTE IMMEDIATE '…'; EXCEPTION WHEN SQLSTATE '42710' THEN NULL; END;`
     (SQLSTATE 42710 = "object already exists").
   - Testcontainers image `icr.io/db2_community/db2:latest`. Add
     `.acceptLicense()` on the container builder; DB2 CE requires it.
   - Container takes 2–3 minutes to start — keep IT setup lean and reuse the
     container across tests via `@Container` static field.

10. **Parameterised IT across dialects**
   - One `@ParameterizedTest` method driving Testcontainers, or 6 IT classes
     (one per dialect) if the lifecycle is cleaner that way. Verify:
     - DDL runs cleanly.
     - DDL is idempotent (run twice, no error).
     - `acquireLock` → `releaseLock` → `DB_LOCK_LATEST` populated → `DB_LOCK`
       row count = 0 → `DB_LOCK_HISTORY` row count = 1.

11. **Docs + CHANGELOG + version bump**
    - Update `README.md` with the supported-DB section.
    - Add a new `## [2.2.0]` section above `[2.1.0]`. (Per Keep a Changelog and
      per the pattern in this project, use the bare release number, not
      `-SNAPSHOT`.)
    - Bump `pom.xml` `<version>` to `2.2.0-SNAPSHOT` if not already.
    - Verify the release profile still builds (`mvn -P release verify`).

12. **Manual smoke test from a consumer**
    - In a sibling `opentmf-db-lock-service` consumer (e.g. the integration
      adapter project), bump to the new snapshot and run its test suite against
      at least PG + H2 to confirm no surprises.

---

## 8. Deliverables checklist

- [ ] `src/main/java/org/opentmf/db/lock/dialect/Dialect.java`
- [ ] `src/main/java/org/opentmf/db/lock/dialect/DialectDetector.java`
- [ ] `src/main/resources/db/postgresql.sql` (renamed + cleaned)
- [ ] `src/main/resources/db/mysql.sql`
- [ ] `src/main/resources/db/oracle.sql`
- [ ] `src/main/resources/db/sqlserver.sql`
- [ ] `src/main/resources/db/db2.sql`
- [ ] `src/main/resources/db/h2.sql`
- [ ] `DbLockProperties` — new `dialect` and `ddlLocation` fields
- [ ] `DbLockAutoConfiguration` — resolve + dispatch
- [ ] `JdbcHelper.createTables(JdbcTemplate, Dialect)` overload
- [ ] Unit tests: `DialectTest`, `DialectDetectorTest`
- [ ] Integration tests: Testcontainers per dialect (PG, MySQL, Oracle, MSSQL, DB2)
- [ ] H2 in-memory IT
- [ ] Testcontainers dependencies added to `pom.xml` (mysql, oracle-xe, mssqlserver, db2)
- [ ] `README.md` updated
- [ ] `CHANGELOG.md` — new `[2.2.0]` section
- [ ] `pom.xml` version bump
- [ ] `mvn clean verify` green locally (all JaCoCo rules satisfied)
- [ ] `mvn -P release verify` still green (source + javadoc jars build)

---

## 9. Risks & open questions

- **Oracle container boot time in CI.** If `gvenzl/oracle-free` adds more than
  a minute to CI, split into a nightly or label-gated job. Measure before
  deciding.
- **DB2 container boot time is the worst offender.** `icr.io/db2_community/db2`
  takes 2–3 minutes to reach "ready" on a cold start. Share the heavy-CI job
  with Oracle and MSSQL. Locally, `.withReuse(true)` plus a Testcontainers
  `~/.testcontainers.properties` entry keeps the same container alive across
  test runs.
- **DB2 license acceptance.** IBM's Community Edition image requires agreeing
  to the DB2 CE licence at container-start time. Use
  `new Db2Container("icr.io/db2_community/db2:latest").acceptLicense()`. The
  acceptance is for CI/test use only and is baked into the Testcontainers
  module — no legal action required from downstream consumers.
- **MySQL timestamp semantics.** No `TIMESTAMP WITH TIME ZONE`. If consumers
  run in non-UTC JVMs, `created_on` can drift. Document as a known limitation
  in `README.md` and `mysql.sql`. An alternative is to use `DATETIME` + always
  write UTC from the app — but that's a bigger refactor touching the Java code.
  Stay out of that scope for this pass.
- **CockroachDB / Aurora-PG / Yugabyte.** These identify as PostgreSQL in
  `DatabaseMetaData`. `DialectDetector` will pick `POSTGRESQL`, and the PG DDL
  should run. Not a risk unless the DDL uses PG-specific features the fork
  doesn't support — current DDL doesn't.
- **Backward compatibility.** `JdbcHelper.createTables(JdbcTemplate)` is `public`
  and probably used by tests in the repo itself. Keep it, mark `@Deprecated`,
  have it delegate. Check external usage via `opentmf-db-lock-service`
  consumers before removing entirely — should be a no-brainer unless someone's
  calling it directly.
- **Version ordering in `CHANGELOG.md`.** This repo's changelog uses bare
  release numbers (`## [2.1.0]`, `## [2.0.0]`), not `-SNAPSHOT`. Continue that
  pattern. Dated with the actual release day, not the day the section is
  written.

---

## 10. Hand-off notes

When the work lands:

1. Cut a release (`2.2.0` suggested).
2. Bump `opentmf-versions` BOM to reference the new `2.2.0`.
3. Notify the `integration-adapter-generator-suite` work track — the `-byod` /
   `-aws-byod` image variants can now proceed. The generator's README/docs
   should mention the five fully-supported consumer DBs by name (PG, MySQL,
   Oracle, MSSQL, DB2) — H2 is dev/test-only, worth noting separately.
4. If there's an opentmf ecosystem announcement channel, a one-line heads-up
   ("multi-DB support in the cluster-lock library") will save downstream teams
   from rediscovering this.

---

## 11. Non-goals worth reaffirming

- Not supporting every SQL dialect in the world. Six is the ceiling for v1
  (PostgreSQL, MySQL/MariaDB, Oracle, SQL Server, DB2, H2).
- Not managing schema migrations. One-shot idempotent creation, nothing else.
- Not embedding Liquibase or Flyway. Customers who want real migrations do that
  at the application layer with `create-tables: false`.
- Not becoming a general-purpose lock framework. ShedLock exists for that case;
  this library stays focused on the `@UsingClusterLock(requestedVersion=…)`
  version-gated skip semantics, which is its reason to exist.
