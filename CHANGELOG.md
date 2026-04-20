# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

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
