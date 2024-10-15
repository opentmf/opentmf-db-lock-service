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

## Usage
The pia-db-lock-library is automatically included from pia-bpmn-sync-service and pia-catalog-sync-service. Therefore, there is no need to include a dependency to it, if the client uses one of the mentioned libraries.

However, it is also possible to directly give a dependency to this library, for certain tasks that require to be performed within a cluster level lock plus to keep version history of successful completions.

### Import pia-commons-versions
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.pia.commons</groupId>
      <artifactId>pia-commons-versions</artifactId>
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
  <groupId>com.pia.commons</groupId>
  <artifactId>pia-db-lock-service</artifactId>
</dependency>
```
### Implementation with `@UsingClusterLock`
`@UsingClusterLock` annotation is provided by the pia-db-lock-service to simplify acquiring and releasing locks. After acquiring the specified lock, it executes the code within the service method and releases the lock after the method ends.

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
- If the acquired lock's version is the same as the requested version:
  - Then the service method will not be executed.
- Else:
  - If one of the following conditions are met:
    - no previous lock of that lockType exists,
    - previous lock version is smaller than the requested
    - previous lock version is greater than the requested and the `downgradeAllowedMillis` duration is met (i.e. rollback is applicable)
  - Then the service method will be executed.
  - Otherwise, the service method will NOT be executed.
- And finally, the acquired lock will be released.
  - If the service method was executed and successful, the `db_lock_latest` record will be updated. 

If you need the details of lock in your service methods, you can add a parameter with `LockContext` type in your methods, then `@UsingClusterLock` will inject the lock details into this new parameter. When calling your service methods in other services, you can initialize a new `LockContext` object and pass it to your method, or you can pass null and `@UsingClusterLock` will initialize a new instance. 

Also, you can provide other parameters to your service methods along with `LockContext` parameter. The order of the parameters is not important. `@UsingClusterLock` will inject the lock details to every parameter with the type of `LockContext`.

```java
import com.pia.db.lock.model.LockContext;

@Slf4j
@Service
public class SomeServiceImpl implements SomeService {

  @UsingClusterLock(lockType = LockType.LOCK_Y, requestedVersion = "#{3 + '.0'}")
  public void performTask(LockContext context) { // @UsingClusterLock will inject lock details
    log.debug("Performing task inside a cluster level lock.");
  }
}
```

Or along with your custom parameters:

```java
import com.pia.db.lock.model.LockContext;

@Slf4j
@Service
public class SomeServiceImpl implements SomeService {

  @UsingClusterLock(lockType = LockType.LOCK_Y, requestedVersion = "#{3 + '.0'}")
  public void performTask(String arg1, LockContext context, Long arg2) {
    
    System.out.println(context.getRequestedVersion());
    
    if (context.isUpgradeRequired()) {
      log.debug("Performing upgrade task inside a cluster level lock.");
      
      if (context.getLatestLock() != null) {
        System.out.println(context.getLatestLock().getLockVersion());
      }
    
    } else {
      log.debug("Performing downgrade task inside a cluster level lock.");
    }
  }
}
```

You can call the above method in other services like below:

```java
import com.pia.db.lock.model.LockContext;

@RestController
@RequiredArgsConstructor
public class SomeOtherServiceImpl implements SomeOtherService {

  private final SomeService someService;

  public void performTask() {
    someService.performTask("test", new LockContext(), 4L); // Initialize yourself
  }

  public void performTask1() {
    someService.performTask("test", null, 4L); // Or pass null
  }
}
```

## Version History
### 1.0.0
- Initial Version
### 1.0.1
- Documentation fixes
### 1.0.2
- Adds `@UsingClusterLock` annotation
