# DB Lock Service
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
pia:
    db-lock:
      create-tables: true
      lock-acquire-poll-interval: 1000
      lock-acquire-timeout: 120000
      lock-hold-timeout: 300000
 ```
The timeout values are in milliseconds and the above table contains the default values. If the default values satisfies the use case of the application, providing those properties is optional.

Similarly, create-tables property is true by default, which causes the required tables to be created automatically at the application start, if they not already exist. The creation script is for PostgreSQL. However, to use the library with other database vendors, it is possible to set this property to false and create the tables through your application mechanism, for example manually, or with the help of liquibase.

## Sample Usage
```java

@RequiredArgsConstructor
public class SomeServiceImpl implements SomeService {

  private final DbLockService dbLockService;

  private void doWithDbLock(String requestedVersion, long downgradeAllowedMillis) throws DbLockException {
    boolean lockReleased = false;
    AcquiredLock lock = null;
    try {
      lock = dbLockService.acquireLock(LockType.LOCK_X, requestedVersion);
      if (lock.isUpgradeRequired(requestedVersion) ||
          lock.isDowngradeRequired(requestedVersion, downgradeAllowedMillis)) {

        // Either upgrade or downgrade.
        // Do what you need to do here.
        ...

        // And then
        releaseLock(lock, true);
        lockReleased = true;
      } else {
        dbLockService.releaseLock(lock, false);
        lockReleased = true;
        log.info("Already up-to-date.", lock.getPreviousLockVersion());
      }
    } catch (Exception e) {
      dbLockService.releaseLock(lock, false);
      lockReleased = true;
      throw new IllegalStateException("Could not perform the task because of exception", e);
    } finally {
      if (!lockReleased) {
        releaseLock(lock, false);
      }
    }
  }

}
```

## Version History
### 1.0.0
- Initial Version
### 1.0.1
- Documentation fixes
