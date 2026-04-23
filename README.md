# OpenTMF DB Lock Service
Helper service for obtaining a cluster level lock using the client application's JDBC datasource.

The library will autoconfigure itself on the condition of spring.datasource.url configuration property.

It allows the acquisition of locks depending on the supported lock types.

The acquired locks are scheduled to be released automatically after a certain timeout is reached, in order to get rid of situations where the caller application cannot release the lock.

## Created Database Tables
This library creates 3 database tables and interacts with them using pure JDBC and manual transaction processing. Spring's JdbcTemplate is just used to obtain the database connections from the underlying DataSource.

| Table Name      | Description                                                |
|-----------------|------------------------------------------------------------|
| DB_LOCK         | Keeps the current locks per lock_type                      |
| DB_LOCK_LATEST  | The latest applied lock & application version relationship |
| DB_LOCK_HISTORY | The history of established and released locks.             |

---

![DB_Lock_Model](src/main/config/model/db-lock-model.jpg)

## Requirements

- Java 17+
- Spring Boot 4.0+

## Supported Configuration Properties

Every property the service recognizes lives under the `opentmf.db-lock` namespace. Everything is optional — the defaults below are what you get without any configuration:

```yaml
opentmf:
  db-lock:
    create-tables: true                 # auto-create the three lock tables on startup
    dialect:                            # optional — auto-detected from JDBC metadata when unset (2.2.0+)
    ddl-location:                       # optional — explicit Spring Resource; overrides `dialect` (2.2.0+)
    lock-acquire-poll-interval: 1000    # ms; how often to re-check whether a held lock has been released
    lock-acquire-timeout:    120000     # ms; give up acquiring the lock after this long
    lock-hold-timeout:       300000     # ms; auto-release a held lock after this long (kill-switch for stuck workers)
    duration-overrides: {}              # per-LockType overrides for the three timeout properties above (1.0.6+)
```

Property-by-property:

| Property                     | Type              | Default   | Notes                                                                                                |
|------------------------------|-------------------|-----------|------------------------------------------------------------------------------------------------------|
| `create-tables`              | `boolean`         | `true`    | Runs the bundled DDL at startup. See **Supported Databases** for the dialect list and caveats.       |
| `dialect`                    | enum (see below)  | *unset*   | Pin the DDL dialect when auto-detection picks wrong (e.g. Aurora-PostgreSQL clones).                 |
| `ddl-location`               | Spring Resource   | *unset*   | Classpath or filesystem path to your own DDL (`classpath:db/my.sql`, `file:/opt/ddl/my.sql`).        |
| `lock-acquire-poll-interval` | `long` (ms)       | `1000`    | Minimum 100 ms.                                                                                      |
| `lock-acquire-timeout`       | `long` (ms)       | `120 000` | Minimum 1 000 ms. Raises `DbLockTimeoutException` on expiry.                                         |
| `lock-hold-timeout`          | `long` (ms)       | `300 000` | Minimum 2 000 ms. Background timer releases the lock when exceeded.                                  |
| `duration-overrides`         | `Map<LockType, …>`| empty     | Each entry overrides the three timeouts above for a single `LockType`. Unset entries inherit.        |

Valid values for `dialect`: `postgresql`, `mysql`, `oracle`, `sqlserver`, `db2`, `h2`.

### Per-lock-type duration overrides

Since 1.0.6, you can tune the three timeouts independently per `LockType`. If an override isn't specified for a lock type, that lock type uses the top-level defaults.

```yaml
opentmf:
  db-lock:
    lock-acquire-poll-interval: 1000
    lock-acquire-timeout: 120000
    lock-hold-timeout: 300000
    duration-overrides:
      lock-x:
        lock-acquire-poll-interval: 100
        lock-acquire-timeout: 1000
        lock-hold-timeout: 2000
      lock-y:
        lock-acquire-poll-interval: 200
        lock-acquire-timeout: 2000
        lock-hold-timeout: 4000
```

## Supported Databases

Starting with version 2.2.0, the library ships creation scripts for the following dialects and auto-detects the active one from `DatabaseMetaData.getDatabaseProductName()`:

| Your DB                     | Minimum config                                                                              |
|-----------------------------|---------------------------------------------------------------------------------------------|
| PostgreSQL                  | nothing — auto-detected                                                                     |
| MySQL / MariaDB             | nothing — auto-detected (run JVM and server in **UTC** — MySQL has no TIMESTAMP WITH TZ)    |
| Oracle 12c+                 | nothing — auto-detected                                                                     |
| Microsoft SQL Server 2016+  | nothing — auto-detected                                                                     |
| IBM DB2 LUW 11.5+           | nothing — auto-detected (run JVM and server in **UTC** — DB2 LUW has no TIMESTAMP WITH TZ)  |
| H2 (dev/test)               | nothing — auto-detected                                                                     |
| Anything else               | set `create-tables: false` and run your own DDL, **or** set `ddl-location`                  |

Resolution order when `create-tables: true` (first match wins):

1. `opentmf.db-lock.ddl-location` — Spring `Resource` location (e.g. `classpath:db/my-custom.sql`, `file:/opt/ddl/custom.sql`). Executed verbatim with `;` as statement separator.
2. `opentmf.db-lock.dialect` — one of `postgresql`, `mysql`, `oracle`, `sqlserver`, `db2`, `h2`. Useful to pin the value when a driver identifies ambiguously (e.g. Aurora-PostgreSQL forks).
3. Auto-detection via JDBC metadata.

Notes:
- **MySQL timestamp semantics.** MySQL has no `TIMESTAMP WITH TIME ZONE`. The shipped DDL uses plain `TIMESTAMP`, which MySQL stores as UTC internally and converts to the session time zone on read. Run both the application JVM and the MySQL server with `time_zone = UTC` to avoid drift.
- **DB2 LUW timestamp semantics.** DB2 LUW has no `TIMESTAMP WITH TIME ZONE` either (that form is DB2 for z/OS only). The shipped DDL uses plain `TIMESTAMP`; same UTC guidance as MySQL.
- **Oracle / DB2 DDL files** use `/` as their statement separator (not `;`) because their scripts contain PL/SQL / compound-SQL blocks with embedded semicolons. This is handled internally by the library — it's only relevant if you edit those files.
- **CockroachDB, Aurora-PostgreSQL, Yugabyte** identify themselves as PostgreSQL; auto-detection picks `POSTGRESQL` and the PG DDL runs. The library's DDL uses no PostgreSQL-specific features these forks don't support.

### Writing your own `ddl-location` script

If you point `opentmf.db-lock.ddl-location` at a DDL file, you own the schema contract. The bundled scripts (under `src/main/resources/db/`) are the reference implementations — copy the closest one and adapt. A custom script must satisfy **all** of the following, otherwise the locking logic breaks in subtle ways:

1. **Idempotency.** The script runs on every startup while `create-tables: true` is set. Use `CREATE TABLE IF NOT EXISTS` (or the dialect's equivalent guard) for every object you create. Running the script twice must be a no-op.
2. **Exact object names.** The runtime SQL refers to tables and columns by these names (case-folded per the target DB's rules):
   - `DB_LOCK(id, lock_type, lock_version, hostname, created_on)`
   - `DB_LOCK_HISTORY(id, lock_id, lock_type, lock_version, hostname, lock_acquired_on, lock_released_on)`
   - `DB_LOCK_LATEST(lock_type, lock_version, hostname, lock_acquired_on)` — with `lock_type` as the primary key.
3. **Unique index on `DB_LOCK.lock_type`.** *This is the locking primitive.* Two concurrent inserts with the same `lock_type` must fail with SQLSTATE class `23` (integrity constraint violation). Without this index, lock acquisition is not atomic and two nodes can both believe they hold the lock. A unique constraint works equally well.
4. **`id` is an auto-increment integer.** The service reads generated keys via `rs.getInt(1)` on the column named `id`. Use `SERIAL`, `INT AUTO_INCREMENT`, `INT IDENTITY(1,1)`, `INT GENERATED BY DEFAULT AS IDENTITY`, etc. — whatever your DB calls it.
5. **`DEFAULT CURRENT_TIMESTAMP` on `created_on` and `DB_LOCK_HISTORY.lock_acquired_on`/`lock_released_on`.** Runtime `INSERT`s don't populate these columns — they rely on the DB default.
6. **Timestamp columns bind to `OffsetDateTime`.** Prefer `TIMESTAMP WITH TIME ZONE`. Plain `TIMESTAMP` works if the JVM and DB are both set to UTC (see the MySQL/DB2 notes above).
7. **Statement separator is `;`.** The `ddl-location` path always uses `;` — the `/` separator used internally for the Oracle/DB2 bundled scripts is tied to their `Dialect` enum entry and does **not** apply to user-supplied files. Structure your statements so no `;` appears inside a logical statement (no unguarded PL/SQL / compound-SQL blocks).
8. **Optional but recommended: `CHECK (lock_type IN ('B','C','X','Y','Z'))`.** Matches the five `LockType` values (`BPMN`, `CATALOG`, `LOCK_X`, `LOCK_Y`, `LOCK_Z`). Prevents a typo in a caller's DB value from silently producing "ghost" locks.

Quickest path: copy `postgresql.sql` (or `h2.sql` if you want a minimal reference) from the library jar, adjust the dialect-specific types (identity, timestamp), and point `ddl-location` at your copy.

## Usage
The opentmf-db-lock-library is automatically included from camunda7-bpmn-sync-service and dnext-catalog-sync-service. Therefore, there is no need to include a dependency to it, if the client uses one of the mentioned libraries.

However, it is also possible to directly give a dependency to this library, for certain tasks that require to be performed within a cluster level lock plus to keep version history of successful completions.

### Import opentmf-commons-versions
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.opentmf</groupId>
      <artifactId>opentmf-versions</artifactId>
      <version>RELEASE</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```
### Add Maven Dependency
```xml
<dependency>
  <groupId>org.opentmf.util</groupId>
  <artifactId>opentmf-db-lock-service</artifactId>
</dependency>
```
### Implementation with `@UsingClusterLock`
`@UsingClusterLock` annotation is provided by the opentmf-db-lock-service to simplify acquiring and releasing locks. After acquiring the specified lock, it executes the code within the service method and releases the lock after the method ends.

Below is: a sample service implementation with `@UsingClusterLock` annotation:

```java
@Service
@Slf4j
public class SomeServiceImpl implements SomeService {

  @UsingClusterLock(lockType = LockType.LOCK_X, requestedVersion = "${test.properties.version}")
  public void performTask() {
    log.debug("Performing task inside a cluster level lock.");
  }
}
```
This code will cause the following:
- A lock will be acquired for lockType = X
- If the lock cannot be acquired within the configured duration or attempts:
  - Then the service method will not be executed.
- Else:
  - If one of the following conditions are met:
    - no previous lock of that lockType exists,
    - previous lock version is lower than the requested
    - previous lock version is equal to the requested and `executeOnSameVersion` flag is set to true
    - previous lock version is greater than the requested and the `downgradeAllowedAfter` duration is met (i.e. rollback is applicable)
  - Then the service method will be executed.
  - Otherwise, the service method will NOT be executed.
- And finally, the acquired lock will be released.
  - If the service method was executed and successful, the `db_lock_latest` record will be updated.

If you need the details of lock in your service methods, you can add a parameter with `LockContext` type in your methods, then `@UsingClusterLock` will inject the lock details into this new parameter. When calling your service methods in other services, you need to initialize a new `LockContext` object and pass it to the method.

Below is the `LockContext` class:
```java
@Getter
@Setter
public class LockContext {

  /**
   * The resolved value of the <code>requestedVersion</code> parameter of <code>@UsingClusterLock
   * </code>.
   */
  private String requestedVersion;

  /**
   * The latest successfully performed lock details. Can be NULL if no previous lock exists.
   */
  private LatestLock latestLock;

  /**
   * Represents the version transition between the previous lock version and the requested version.
   */
  private VersionTransition versionTransition;

  /**
   * Indicates whether the annotated method considers its execution successful. Defaults to
   * <b>true</b>. When the business logic completes normally but determines that no effective
   * change was applied (e.g. all target resources were already up-to-date), it may call
   * {@code setSuccess(false)} to signal that the lock must be released <em>without</em>
   * recording the requested version as the latest completed version. On the next run with the
   * same version, the method will be re-executed instead of being short-circuited.
   *
   * <p>Ignored when the annotated method does not declare a {@link LockContext} parameter or
   * when it throws an exception (which always releases the lock with success=false).
   */
  private boolean success = true;

  /**
   * Returns true if this is the initial lock that we have acquired, false otherwise.
   * @return true if this is the initial lock that we have acquired, false otherwise.
   */
  public boolean isInitial() {
    return latestLock == null;
  }

  /**
   * Returns true is this is an upgrade.
   * <p>
   * An upgrade means, either there is no successfully executed previous lock, or the current
   * requested lock version is greater than the previous successful lock.
   * </p>
   *
   * @return true is this is an upgrade, false otherwise.
   */
  public boolean isUpgrade() {
    return versionTransition == VersionTransition.UPGRADE;
  }

  /**
   * Returns true is this is a downgrade and this downgrade is allowed to be executed.
   * <p>
   * A downgrade which means the requested version is saller than the latest successfully completed
   * version and the downgradeAllowedAfter has been reached. So we must be going for a downgrade.
   * </p>
   *
   * @return Returns true is this is a downgrade and this downgrade is allowed to be executed, false
   * otherwise.
   */
  public boolean isDowngrade() {
    return versionTransition == VersionTransition.DOWNGRADE;
  }

  /**
   * Returns true if the requested and obtained lock version is the same as the latest successfully
   * executed lock version. There can be cases where a business logic must be executed each time a
   * lock is obtained, even though it is the same as the latest lock version.
   *
   * @return true if the requested and obtained lock version is the same as the latest successfully
   * * executed lock version, false otherwise.
   */
  public boolean isSameVersion() {
    return versionTransition == VersionTransition.SAME_VERSION;
  }
}

```
And below is the contents of the `LatestLock` class:
```java
/**
 * The latest successfully performed lock's details.
 *
 * @author Gokhan Demir
 */
@RequiredArgsConstructor
@Getter
@ToString
public class LatestLock {

  /**
   * The latest successfully performed lock's version.
   */
  private final String lockVersion;

  /**
   * The latest successfully performed lock was release at this date-time.
   */
  private final OffsetDateTime lockReleasedAt;
}
```
An example of reaching the attributes of the LockContext inside the service method implementation that contains a LockContext parameter:

```java
@Slf4j
@Service
public class SomeServiceImpl implements SomeService {

  @UsingClusterLock(lockType = LockType.LOCK_Y, requestedVersion = "${test.properties.version}")
  public void performTask(String arg1, LockContext ctx, Long arg2) {

    // log resolved requestedLock version string
    log.debug("Requested lock version: {}, previous lock: {}",
            ctx.getRequestedVersion(), ctx.getLatestLock());
    
    if (ctx.isInitial()) {
      // POST
    } else {
      // PATCH
    }
  }
}
```
You can call above method in other services as below;

```java
@RestController
@RequiredArgsConstructor
public class SomeOtherServiceImpl implements SomeOtherService {

  private final SomeService someService;

  public void performTask() {
    someService.performTask("test", new LockContext(), 4L);
  }
}
```

If you want your service method to be executed even if the previous lock version and requested version are the same, you can set the `executeOnSameVersion` flag to true in the `@UsingClusterLock` annotation.

```java


@Slf4j
@Service
public class SomeServiceImpl implements SomeService {

  @UsingClusterLock(
      lockType = LockType.LOCK_X,
      requestedVersion = "${test.properties.version}",
      executeOnSameVersion = true)
  public void performTask(LockContext ctx) {

    if (ctx.isInitial()) {
      log.debug("This is the initial obtained lock for the requested lock type. 
          + "Perform the initial tasks.");

    } else if (ctx.isUpgrade()) {
      log.debug("The requested version is higher than the previous lock version."
          + "Do the upgrade.");

    } else if (ctx.isDowngrade()) {
      log.debug("The requested version is lower than the previous lock version "
          + "and enough milliseconds has already passed to allow a downgrade. "
          + "Do the downgrade.");

    } else if (ctx.isSameVersion()) {
      log.debug("The requested version is the same as the previous lock version."
          + "Do whatever you need to do within the obtained lock");
    }
  }
}
```

### Signalling a no-op run with `LockContext.success`

By default, a successful return from the annotated method causes the lock to be released with the requested version recorded as the latest completed one (row inserted/updated in `DB_LOCK_LATEST`). On the next run with the same version, the method is short-circuited.

If your method completes normally but determines that **nothing effective was applied** (for example, all target resources were already up-to-date), you can call `context.setSuccess(false)` before returning. The lock is released as usual, but the `DB_LOCK_LATEST` record is **not** updated — so the next run with the same version will re-execute the method.

```java
@UsingClusterLock(lockType = LockType.LOCK_X, requestedVersion = "${app.catalog-version}")
public void syncCatalog(LockContext ctx) {
  int touched = doSync();
  if (touched == 0) {
    // Ran to completion but made no effective changes: don't record this version as applied.
    ctx.setSuccess(false);
  }
}
```

Notes:
- The default is `true`, so existing methods that don't declare a `LockContext` parameter are unaffected.
- Throwing an exception always releases the lock with `success=false` regardless of what `ctx.isSuccess()` says.

### Wrapping failures with `failureMessage`

`acquireLock` is declared `throws DbLockException` (checked). If an annotated method doesn't declare or catch `DbLockException`, Spring AOP surfaces it as an `UndeclaredThrowableException`, which is awkward for callers. Similarly, business exceptions propagate raw through the aspect.

Setting `failureMessage` makes the aspect wrap any exception from inside the flow as `IllegalStateException(failureMessage, cause)`:

```java
@UsingClusterLock(
    lockType = LockType.CATALOG,
    requestedVersion = "${app.catalog-version}",
    failureMessage = "Could not synchronize Catalogs")
public void syncCatalog(LockContext ctx) { ... }
```

- `DbLockException` from lock acquisition → `IllegalStateException("Could not synchronize Catalogs", dbLockEx)`.
- Any exception from the method body → same wrap.
- Default empty → no wrap (current behavior).

### `downgradeAllowedAfter` requires an ISO-8601 duration

The `downgradeAllowedAfter` annotation value must be an ISO-8601 duration string — the format produced by `java.time.Duration.toString()` and accepted by `Duration.parse(...)`:

```java
@UsingClusterLock(lockType = LockType.LOCK_X, requestedVersion = "1.0",
    downgradeAllowedAfter = "PT10M") // ten minutes
public void task() { ... }
```

Property placeholders (`${...}`) and SpEL (`#{...}`) are resolved first, so bindings from a `Duration`-typed `@ConfigurationProperties` field also work without manual conversion. The full Spring placeholder grammar is supported, including default values:

```java
@UsingClusterLock(lockType = LockType.LOCK_X,
    requestedVersion = "${app.version}",
    downgradeAllowedAfter = "${app.downgrade-allowed-after:PT10M}")
public void task() { ... }
```

When the property is absent from the environment, the default (`PT10M` above) applies.

## Version History
See [CHANGELOG.md](CHANGELOG.md) for detailed version history.
