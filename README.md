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

## Supported Configuration Properties

The following configuration properties are recognized by the service:

```yaml
opentmf:
    db-lock:
      create-tables: true
      lock-acquire-poll-interval: 1000
      lock-acquire-timeout: 120000
      lock-hold-timeout: 300000
 ```
The timeout values are in milliseconds and the above table contains the default values. If the default values satisfies the use case of the application, providing those properties is optional.

Similarly, create-tables property is true by default, which causes the required tables to be created automatically at the application start, if they not already exist. The creation script is for PostgreSQL. However, to use the library with other database vendors, it is possible to set this property to false and create the tables through your application mechanism, for example manually, or with the help of liquibase.

Starting from version 1.0.6, you can override the lock-acquire-poll-interval, lock-acquire-timeout and lock-hold-timeout values per supported lock type.

For example, if you want to override the default values for the particular lock type = LOCK_X and LOCK_Y you can define the following properties:

```yaml
opentmf:
    db-lock:
      create-tables: true
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
If an overridden duration is not specified for a particular lock type, then the default values are used, which pertains the old behaviour. 

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
