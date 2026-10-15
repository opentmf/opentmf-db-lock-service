# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [3.0.0] - 2026-10-15

A review of the library's transaction handling. Nothing here changes how a
lock is taken — a single `INSERT` guarded by the unique index on `lock_type`
— but the transactions around it now end explicitly on every path, and the
decisions the lock makes no longer depend on the pool's defaults, the
isolation level or the JVM's clock.

### Removed
- **`JdbcHelper.createTables(JdbcTemplate)`**, deprecated for removal since
  2.2.0. Removing published API is a binary-compatibility break, which is why
  this release is 3.0.0 rather than 2.2.3; nothing else in it breaks binary
  compatibility. Callers move to `createTables(JdbcTemplate, Dialect)` —
  `Dialect.POSTGRESQL` reproduces what the removed overload did — or to
  `createTables(JdbcTemplate, Resource)` for a script of their own.

### Fixed
- **The lock tables and the `lock_version` widening persist under a pool
  whose auto-commit default is off** (Hikari `auto-commit: false`). The DDL
  ran on a raw pool connection and was never committed; such a pool rolls
  back whatever is open when the connection is returned, and where DDL is
  transactional that undid it. On SQL Server and DB2 the bundled tables were
  never created — startup carried on and the first `acquireLock` failed — and
  on PostgreSQL, SQL Server and DB2 a `ddl-location` script without its own
  `COMMIT` was lost (both since 2.2.0). On PostgreSQL the pre-2.0.0
  `VARCHAR(10)` → `VARCHAR(50)` widening was rolled back on every start while
  logging that it had run (2.2.2 only; 2.2.0 and 2.2.1 ran it inside the
  script's own transaction). MySQL/MariaDB, Oracle and H2 commit DDL
  implicitly and were never affected, nor was the bundled PostgreSQL script.
  The DDL and the widening now each run as one transaction the library owns:
  committed explicitly, rolled back as a whole on any failure, the
  connection's auto-commit restored.
- **Instances starting together against an empty schema all start.**
  `CREATE TABLE IF NOT EXISTS` is not safe against a concurrent creator on
  PostgreSQL: once the first instance committed its tables, the others failed
  with a duplicate key in `pg_class` and their startup failed (two out of
  three, every time, measured). A failed DDL run is now rolled back and run
  once more, which finds the tables. Present in every release.
- **Every recorded release after the first updates `DB_LOCK_LATEST` on every
  dialect.** The update used PostgreSQL-only `UPDATE … FROM`, so on
  MySQL/MariaDB, Oracle, SQL Server, DB2 and H2 the second recorded release of
  a lock type failed: it rolled back, the lock stayed held until its
  hold-timeout, and the latest version froze at the first one recorded. Under
  `@UsingClusterLock` every later version's work re-ran on each start and
  reported a failure. Since 2.2.0.
- **A waiter decides on the version the previous holder recorded** (2.2.1,
  2.2.2). When `acquireLock` found nothing to reclaim it left the transaction
  of that check open across the poll sleep, and its next insert and its read
  of `DB_LOCK_LATEST` ran inside it. Under a snapshot isolation level —
  MySQL/MariaDB's default REPEATABLE READ, or a pool configured for it — a
  waiter that won the lock after the holder recorded its version read the
  version from before, took it for an upgrade and ran the same work a second
  time. On PostgreSQL the waiter also sat `idle in transaction` for every poll
  interval holding locks on `DB_LOCK`, queueing any DDL against the table, and
  an `idle_in_transaction_session_timeout` shorter than the poll interval
  killed it instead of letting it wait.
- **A lock's age is measured with the database's clock.** The stale-lock
  reclaim (since 2.2.1) and the startup sweep compared `created_on`, stamped by
  the database, with the JVM's clock. With a plain `TIMESTAMP` column
  (MySQL/MariaDB, DB2, since 2.2.0) read in a time zone other than the
  server's, a lock one second old looked hours old and another instance took it
  from its live holder — measured: 38 ms after it was acquired. Clock skew
  between hosts shifted the decision the same way on every database.
- **DB2: timestamps are read back at all.** DB2's JCC driver refuses to read a
  plain `TIMESTAMP` as `OffsetDateTime`, which every read of a lock's or the
  latest version's time did. So on DB2, once a version had been recorded for a
  lock type, every later `acquireLock` of that type failed with "Database error
  while acquiring lock"; a stale lock was never reclaimed; and the startup sweep
  failed whenever a lock row existed. Since 2.2.0 — the dialect ITs never read
  a timestamp back. Such a column is now read as a `LocalDateTime` in the JVM's
  time zone (`JdbcHelper.getOffsetDateTime`). That is only the true instant
  when the JVM and the DB2 server run in the same time zone — which is what the
  README already asks for DB2's plain `TIMESTAMP`; otherwise the timestamps you
  read back are shifted. The lock's own decisions do not depend on it: they
  compare two values of the same row, both read this way.
- **The `downgradeAllowedAfter` period is measured with the database's clock**
  for the same reason: the latest-version record is stamped by the database,
  and on MySQL/MariaDB or DB2 under a time-zone mismatch (since 2.2.0) a
  version recorded seconds ago could already allow a downgrade, or one
  recorded long ago not yet.
- **An interrupted waiter gives up.** The poll sleep swallowed the interrupt
  and re-set the flag, so every later sleep returned at once and the loop
  issued failed inserts at full speed until the acquire timeout — 6,396 in a
  3-second window, measured, against 4 normally. It now ends with a
  `DbLockException` and keeps the interrupt flag. Present in every release.
- **No transaction is left for the pool to finish.** A database error other
  than the unique-index violation during acquisition escaped past the rollback
  (since 2.0.0), so the connection was closed mid-transaction and the pool
  decided what happened to it: Hikari and DBCP2 roll it back, Tomcat JDBC by
  default does not, and a raw Oracle connection commits on close. Every step now rolls back on any failure, unchecked
  exceptions included.
- **Racing releasers leave one history row.** Release, reclaim and the startup
  sweep copy the lock row into `DB_LOCK_HISTORY` before deleting it; when the
  hold-timeout fired just as the holder released, or two waiters reclaimed the
  same stale lock (since 2.2.1), both copied it. The one whose delete finds the
  row gone now rolls its copy back.
- **A lock taken after shutdown is given back.** After `destroy()` no
  hold-timeout can be armed, so such a lock could only ever be freed by
  another instance's stale reclaim; the acquire now fails and releases it.

### Changed
- **One short transaction per step.** Each attempt at a lock, each reclaim,
  each release, `hasLock` and each row of the startup sweep runs in its own
  transaction on a connection borrowed for it alone, at READ COMMITTED, and
  given back with its own auto-commit and isolation restored (a pool that does
  not reset them on return no longer leaks auto-commit off to the
  application). A thread waiting for a lock no longer holds a pooled
  connection for the whole wait; it borrows one per attempt.
- **Hold-timeouts run on one daemon thread per service instance** instead of a
  non-daemon `java.util.Timer` thread per held lock. A hold-timeout whose
  release fails is no longer kept, with its thread, for `destroy()` to retry.
- A recorded release that finds its lock already gone now warns that the work
  done under it was not recorded in `DB_LOCK_LATEST`, naming the hold-timeout.
- `db/postgresql.sql` no longer carries its own `BEGIN` / `COMMIT`.
- **Spring Boot BOM 4.1.0 → 4.1.1**, so consumers inheriting versions from
  this library see its patch-level upgrades. ArchUnit 1.5.1 and the current
  compiler, surefire, failsafe, deploy, Sonar and Central-publishing plugins
  on the build side.
- README: what `lock-hold-timeout` means — it is both the holder's kill-switch
  and everyone else's staleness threshold, so work that outruns it loses the
  lock while still running; and why `@UsingClusterLock` must not run inside a
  caller's transaction.

### Added
- `JdbcHelper.inTransaction(JdbcTemplate, SqlWork)` and `JdbcHelper.SqlWork`.
- `Dialect.getCurrentTimestampSql()` and `DialectDetector.fromConnection(Connection)`.
- `LatestLock.getReadAt()` and a three-argument constructor; the two-argument
  one is kept.
- `JdbcHelper.getOffsetDateTime(ResultSet, String)`.

## [2.2.2] - 2026-08-10

### Fixed
- **PostgreSQL: no more `ACCESS EXCLUSIVE` table lock on every startup.** The
  bundled `db/postgresql.sql` re-ran the legacy `lock_version`
  `VARCHAR(10)` → `VARCHAR(50)` widening unconditionally on each boot (present
  since 2.0.0). Each `ALTER` takes an `ACCESS EXCLUSIVE` lock even when the
  column is already 50, which could form a lock cycle and surface as
  `PSQLException: ERROR: deadlock detected` at startup when several
  application contexts share one database and boot concurrently. The widening
  has moved out of the DDL into the new `LockVersionMigration`, which reads
  the column width through JDBC metadata and issues the `ALTER` only when the
  column is genuinely narrower than 50. Fresh and already-migrated installs
  now execute no statement at all, so they take the lock zero times; a legacy
  `VARCHAR(10)` install is still widened once. A column an operator
  deliberately widened past 50 is left alone rather than narrowed back.

### Added
- `LockVersionMigration` (package `org.opentmf.db.lock.dialect`), invoked
  automatically by `JdbcHelper.createTables(JdbcTemplate, Dialect)`. Scoped to
  the connection's own catalog and schema, so a same-named legacy table
  belonging to another tenant in the same database cannot trigger a widening.
  A no-op for every dialect other than PostgreSQL — no other dialect ever had
  a `VARCHAR(10)` era.

### Changed
- `db/postgresql.sql` stays plain, `;`-separated DDL with no PL/pgSQL `DO`
  block, so it remains runnable on PostgreSQL-compatible engines that do not
  support anonymous blocks, and remains usable as a `ddl-location` template.
- **Spring Boot BOM 4.0.5 → 4.1.0.** Every BOM-managed dependency moves with
  it, so consumers inheriting versions from this library will see transitive
  upgrades (Spring Framework, Jackson, Logback, the JDBC drivers). The library
  itself is source- and behaviour-compatible; the full test matrix — including
  the `heavy-it` Oracle / SQL Server / DB2 suites — passes unchanged.
- Build tooling upgraded: ArchUnit 1.5.0, JaCoCo 0.8.15, Sonar scanner
  5.7.0.6970, Surefire/Failsafe 3.5.6, Enforcer 3.6.3, and the Central
  publishing plugin 0.11.0.

## [2.2.1] - 2026-06-26

### Added
- **On-demand stale-lock reclaim during acquisition.** When `acquireLock`
  finds the lock already held and that lock has exceeded its configured
  `lock-hold-timeout`, it now releases the stale row (recording it in
  `DB_LOCK_HISTORY` first) and retries the insert immediately, instead of
  retrying blindly until the acquire-timeout elapses. This recovers locks
  left behind by crashed, OOM-killed, or hung holders without waiting for an
  application restart — the on-demand counterpart to the startup-only
  `removeStaleLocks()`. The reclaim deletes by the specific stale lock id
  (never by `lock_type` alone), so a fresh lock acquired by another instance
  in the meantime is never disturbed.

## [2.2.0]

### Added
- **Multi-database DDL support.** The library now ships creation scripts for
  MySQL/MariaDB, Oracle (12c+), Microsoft SQL Server (2016+), IBM DB2 LUW
  (11.5+), and H2 in addition to PostgreSQL. When
  `opentmf.db-lock.create-tables=true`, the active dialect is auto-detected
  from the JDBC connection metadata and the matching DDL runs. Runtime lock
  and unlock queries were already SQL-92 compliant, so no algorithm changes
  were needed.
- New property `opentmf.db-lock.dialect` — explicit override for
  auto-detection (useful when the driver identifies ambiguously, e.g.
  Aurora-PostgreSQL clones).
- New property `opentmf.db-lock.ddl-location` — Spring `Resource` location of
  a user-supplied DDL file, taking precedence over both auto-detection and
  the `dialect` setting. For dialects the library does not ship out of the
  box.
- New `Dialect` enum and `DialectDetector` utility published under
  `org.opentmf.db.lock.dialect`.
- New `JdbcHelper.createTables(JdbcTemplate, Dialect)` and
  `JdbcHelper.createTables(JdbcTemplate, Resource)` overloads.
- Testcontainers-backed integration tests across every supported dialect.
  PostgreSQL, MySQL, and H2 run in the default build; Oracle, SQL Server, and
  DB2 are gated behind the `heavy-it` Maven profile
  (`mvn -P heavy-it verify`) because of their container boot times.

### Changed
- `src/main/resources/db/creation_script.sql` renamed to
  `db/postgresql.sql`. The initial `CREATE TABLE` definitions now declare
  `lock_version` as `VARCHAR(50)` directly, while the idempotent
  `ALTER … TYPE` statements introduced in 2.0.0 are retained so installs
  upgrading straight from a 1.x release (original `VARCHAR(10)`) are still
  widened correctly.
- Documentation on `DbLockProperties.createTables` no longer implies
  PostgreSQL is the only supported target — the full dialect list is
  documented.

### Deprecated
- `JdbcHelper.createTables(JdbcTemplate)` — use the new
  `createTables(JdbcTemplate, Dialect)` or
  `createTables(JdbcTemplate, Resource)` overloads. The old one defaults to
  `Dialect.POSTGRESQL` for source compatibility and will be removed in a
  future major.

## [2.1.0] - 2026-04-17

### Added
- `LockContext.success` (default `true`). When a method annotated with `@UsingClusterLock` declares a `LockContext` parameter, it may call `context.setSuccess(false)` to release the lock without recording the requested version as the latest successful one — useful when the method ran but determined that no effective change was applied.
- `@UsingClusterLock.failureMessage` (default empty). When non-empty, any exception raised from inside the aspect — including checked `DbLockException` from `acquireLock` and any exception thrown by the annotated method — is wrapped as `new IllegalStateException(failureMessage, cause)` before propagation. Relieves callers from declaring or catching `DbLockException` and avoids `UndeclaredThrowableException` from Spring AOP.
- Property placeholders in `@UsingClusterLock` values now support Spring's default-value syntax, e.g. `"${opentmf.catalog-sync.downgrade-allowed-after:PT10M}"`. The aspect now delegates to `Environment.resolveRequiredPlaceholders`, so the full Spring placeholder grammar (defaults, nested placeholders, multiple placeholders per string) is available.

### Changed
- **Breaking**: Renamed `@UsingClusterLock.downgradeAllowedMillis` to `downgradeAllowedAfter`, and its value is now required to be an ISO-8601 duration string (e.g. `"PT10M"`). Plain millisecond counts are no longer accepted. Default changed from `"600000"` to `"PT10M"` (equivalent). Callers must update both the field name and any numeric values. Property placeholders and SpEL resolution still apply before parsing.

### Fixed
- Release-lock failures inside the aspect's error-handling path are now attached as suppressed exceptions instead of replacing the primary cause. Previously a `DbLockException` from the cleanup `releaseLock` call could mask the original business exception.

## [2.0.0] - 2026-03-23

### Added
- Stale lock cleanup at startup. Locks orphaned by non-graceful shutdowns (kill -9, OOM kill, node eviction) are now automatically detected and removed during auto-configuration, respecting per-type `lockHoldTimeout` overrides.
- `release` Maven profile for source, javadoc, GPG signing, and central publishing plugins.
- `LICENSE` file (Apache 2.0).

### Changed
- **Breaking**: Upgraded Spring Boot from 3.5.5 to 4.0.4.
- Removed `spring-boot-starter-parent` inheritance; now uses `spring-boot-dependencies` BOM import for dependency management.
- Renamed `spring-boot-starter-aop` dependency to `spring-boot-starter-aspectj` (renamed in Spring Boot 4).
- Updated `@AutoConfiguration` to use string-based `afterName` for `LiquibaseAutoConfiguration` due to package relocation in Spring Boot 4.
- `resolveProperty()` in `UsingClusterLockAnnotationAspect` now validates `${}` and `#{}` patterns for well-formedness and fails fast with a clear error when a property is not found.
- Changed aspect advice method `wrapWithLock` visibility from `private` to `public` to comply with AspectJ conventions.
- Widened `lock_version` column from `VARCHAR(10)` to `VARCHAR(50)` in all three tables via `ALTER COLUMN` (applies to both new and existing installations).
- Replaced deprecated `org.springframework.lang.NonNull` with `org.jspecify.annotations.NonNull`.
- Documented that version comparison in `AcquiredLock` is lexicographic (callers should use zero-padded version strings when numeric ordering matters).
- Improved exception messages in lock acquisition error paths.
- License URL updated to HTTPS.

### Removed
- Removed no-op `@Transactional` annotation from `DbLockCancelTimer` inner class (not a Spring-managed bean).

### Fixed
- `ConcurrentModificationException` in `DbLockServiceImpl.destroy()` when multiple locks are held at shutdown.
- `JdbcHelper.commit()` now propagates `SQLException` instead of silently swallowing it, preventing silent data loss on failed commits.
- Race conditions in timer map: replaced `HashMap` with `ConcurrentHashMap` to eliminate data races between timer threads and lock acquire/release operations.
- Lock acquisition retry loop now only retries on integrity constraint violations (SQLSTATE class 23); other database errors fail immediately instead of being masked as "lock held".
- Lock acquisition retry now rolls back the connection after a constraint violation, fixing PostgreSQL's "current transaction is aborted" error on subsequent retries.
- `hasLock()` now properly rolls back the read-only transaction before closing the connection.
- Unclosed `ResultSet` in `JdbcHelper.logTables()`.
- `JdbcHelper.createTables()` now obtains connections directly from `DataSource` instead of through `DataSourceUtils`.

## [1.0.9]

### Added
- `boolean hasLock(LockType lockType)` method on `DbLockService`.

## [1.0.8]

### Changed
- Initial open-source version.

## [1.0.7]

### Fixed
- Updated creation script for PostgreSQL to include begin and commit transaction.

## [1.0.6]

### Added
- Per lock type overrides for `lockAcquirePollInterval`, `lockAcquireTimeout` and `lockHoldTimeout`.

### Changed
- Updated Spring Boot to version 3.4.0.

## [1.0.5]

### Changed
- **Breaking**: Updated execution logic of `@UsingClusterLock`, adds `executeOnSameVersion` flag to be able to execute the service method even if the lock version is not changed.
- Introduced `VersionTransition` enum to represent a version transition between two versions.
- Updated the `LockContext` class, added `versionTransition` attribute and removed `upgrade` field.
- Updated `AcquiredLock` class, added new methods to calculate the version change and to check if downgrade is allowed. Removed methods taking lockVersion as a parameter, since now AcquiredLock also contains this information.

## [1.0.4]

### Changed
- Updated dependent library versions to their latest.

## [1.0.3]

### Added
- Support for `LockContext` parameter in methods annotated with `@UsingClusterLock` to retrieve lock details.

## [1.0.2]

### Added
- `@UsingClusterLock` annotation.

## [1.0.1]

### Fixed
- Documentation fixes.

## [1.0.0]
- Initial version.
