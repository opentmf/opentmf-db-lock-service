# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

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
