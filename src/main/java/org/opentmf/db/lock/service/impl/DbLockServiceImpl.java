package org.opentmf.db.lock.service.impl;


import org.opentmf.db.lock.config.DbLockProperties;
import org.opentmf.db.lock.dialect.Dialect;
import org.opentmf.db.lock.dialect.DialectDetector;
import org.opentmf.db.lock.exception.DbLockException;
import org.opentmf.db.lock.exception.DbLockLostException;
import org.opentmf.db.lock.exception.DbLockTimeoutException;
import org.opentmf.db.lock.model.AcquiredLock;
import org.opentmf.db.lock.model.LatestLock;
import org.opentmf.db.lock.model.LockType;
import org.opentmf.db.lock.service.api.DbLockService;
import org.opentmf.db.lock.util.DurationHelper;
import org.opentmf.db.lock.util.JdbcHelper;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @author Gokhan Demir
 */
@Slf4j
public class DbLockServiceImpl implements DbLockService, DisposableBean {

  private final JdbcTemplate jdbcTemplate;
  private final DurationHelper lockHelper;
  private final Dialect configuredDialect;

  /** Resolved once, from the first connection that needs them. */
  private final AtomicReference<AgeQueries> ageQueries = new AtomicReference<>();

  public DbLockServiceImpl(JdbcTemplate jdbcTemplate, DbLockProperties dbLockProperties) {
    this.jdbcTemplate = jdbcTemplate;
    lockHelper = new DurationHelper(dbLockProperties);
    configuredDialect = dbLockProperties.getDialect();
  }

  /** The armed hold-timeout of every lock this instance holds. */
  private final Map<AcquiredLock, HoldTimeout> holdTimeouts = new ConcurrentHashMap<>();

  private final ScheduledThreadPoolExecutor holdTimeoutScheduler = newHoldTimeoutScheduler();

  private static final String SQL_LOCK_COUNT = "select count(*) from DB_LOCK where lock_type = ?";

  private static final String SQL_LATEST_LOCK_COUNT =
      "select count(*) from DB_LOCK_LATEST where lock_type = ?";

  private static final String SQL_INSERT_LATEST_LOCK =
      "insert into DB_LOCK_LATEST (lock_type, lock_version, hostname, lock_acquired_on) "
          + "select lock_type, lock_version, hostname, created_on from DB_LOCK "
          + "where id = ?";

  /**
   * Plain scalar subqueries rather than {@code UPDATE ... FROM}, which is PostgreSQL syntax: MySQL,
   * Oracle, DB2 and H2 have no such form, and SQL Server rejects the aliased target. Every
   * placeholder takes the same lock id. When that lock row is gone, the {@code where} matches
   * nothing, exactly as the join used to.
   */
  private static final String SQL_UPDATE_LATEST_LOCK = "update DB_LOCK_LATEST "
      + "set lock_version = (select L.lock_version from DB_LOCK L where L.id = ?), "
      + "hostname = (select L.hostname from DB_LOCK L where L.id = ?), "
      + "lock_acquired_on = (select L.created_on from DB_LOCK L where L.id = ?) "
      + "where lock_type = (select L.lock_type from DB_LOCK L where L.id = ?)";

  private static final String SQL_INSERT_LOCK =
      "insert into DB_LOCK(lock_type, lock_version, hostname) values (?, ?, ?)";

  private static final String SQL_DELETE_LOCK =
      "delete from DB_LOCK where id = ? and lock_type = ?";

  /**
   * Changes nothing; it takes the lock row's write lock, held until the transaction ends. A renewal
   * and a stale-lock removal of the same row each start with it and then re-read the row, so the
   * second of them waits for the first and decides on what the first committed: a removal that
   * meets a renewal in flight sees the renewed lease, and a renewal that comes after a removal
   * finds no row.
   */
  private static final String SQL_LOCK_ROW =
      "update DB_LOCK set lock_version = lock_version where id = ?";

  private static final String SQL_INSERT_HISTORY =
      "insert into DB_LOCK_HISTORY (lock_id, lock_type, lock_version, hostname, lock_acquired_on) "
          + "select id, lock_type, lock_version, hostname, created_on "
          + "from DB_LOCK "
          + "where DB_LOCK.id = ?";

  /**
   * The reads behind every time-based decision — is a held lock stale, how long ago was the latest
   * version recorded — each also returning the database's own current time as {@code db_now}, so
   * an age is measured with the clock that stamped the row. For a database the library does not
   * know they return no {@code db_now} and the JVM's clock stands in, as before.
   */
  private record AgeQueries(String selectLockByType, String selectAllLocks,
      String selectLockById, String selectLatestLock, String renewLock, boolean dbClock) {

    static AgeQueries forDialect(Dialect dialect) {
      if (dialect == null) {
        return new AgeQueries(
            "select id, lock_version, hostname, created_on from DB_LOCK where lock_type = ?",
            "select id, lock_type, lock_version, hostname, created_on from DB_LOCK",
            "select id, lock_type, lock_version, hostname, created_on from DB_LOCK where id = ?",
            "select lock_version, lock_acquired_on from DB_LOCK_LATEST where lock_type = ?",
            renewLockSql("CURRENT_TIMESTAMP"),
            false);
      }
      String now = dialect.getCurrentTimestampSql();
      return new AgeQueries(
          "select id, lock_version, hostname, created_on, " + now
              + " as db_now from DB_LOCK where lock_type = ?",
          "select id, lock_type, lock_version, hostname, created_on, " + now
              + " as db_now from DB_LOCK",
          "select id, lock_type, lock_version, hostname, created_on, " + now
              + " as db_now from DB_LOCK where id = ?",
          "select lock_version, lock_acquired_on, " + now
              + " as db_now from DB_LOCK_LATEST where lock_type = ?",
          renewLockSql(now),
          true);
    }

    /**
     * Restarts the lease: the lock's age is measured from {@code created_on}, by every version of
     * the library, so an instance still on an older release honours a renewal too. The time is
     * the database's, the same clock as the column's default.
     */
    private static String renewLockSql(String now) {
      return "update DB_LOCK set created_on = " + now + " where id = ?";
    }

    OffsetDateTime now(ResultSet rs) throws SQLException {
      return dbClock ? JdbcHelper.getOffsetDateTime(rs, "db_now") : OffsetDateTime.now();
    }

    /** When the row was read by the database's clock; null without it, for the JVM to stand in. */
    OffsetDateTime readAt(ResultSet rs) throws SQLException {
      return dbClock ? JdbcHelper.getOffsetDateTime(rs, "db_now") : null;
    }
  }

  private AgeQueries ageQueries(Connection conn) throws SQLException {
    AgeQueries queries = ageQueries.get();
    if (queries == null) {
      Dialect dialect = configuredDialect != null
          ? configuredDialect
          : DialectDetector.fromConnection(conn).orElse(null);
      if (dialect == null) {
        log.warn("Unrecognised database '{}': the age of a lock is judged by this JVM's clock, so "
                + "clock skew or a time-zone mismatch with the server can make a live lock look "
                + "stale. Set opentmf.db-lock.dialect if the database is a supported one.",
            conn.getMetaData().getDatabaseProductName());
      }
      queries = AgeQueries.forDialect(dialect);
      ageQueries.compareAndSet(null, queries);
    }
    return queries;
  }

  @Override
  public AcquiredLock acquireLock(LockType lockType, String lockVersion) throws DbLockException {
    log.debug("Attempting to acquire lock for lockType = {}, and lockVersion = {}",
        lockType, lockVersion);
    long t0 = System.currentTimeMillis();
    while (true) {
      AcquiredLock acquiredLock = tryToLock(lockType, lockVersion);
      if (acquiredLock != null) {
        armHoldTimeout(acquiredLock);
        log.debug("Acquired lock id = {} for lockType = {}, and lockVersion = {}.",
            acquiredLock.getLockId(), lockType, lockVersion);
        return acquiredLock;
      }
      if ((System.currentTimeMillis() - t0) > lockHelper.getLockAcquireTimeout(lockType)) {
        String message = String.format("Cannot acquire DB Lock for %s within the configured " +
            "%d millis. Giving up.", lockType, lockHelper.getLockAcquireTimeout(lockType));
        throw new DbLockTimeoutException(message);
      }
      if (!reclaimStaleLock(lockType)) {
        log.debug("Sleeping {} milliseconds before re-attempting to acquire {} lock.",
            lockHelper.getLockAcquirePollInterval(lockType), lockType);
        sleepUntilNextPoll(lockType);
      }
    }
  }

  /**
   * One attempt at the lock, as its own short transaction: the inserted lock together with the
   * latest version recorded so far, or {@code null} when the unique index on {@code lock_type}
   * rejected the insert because another instance holds the lock. The connection goes back to the
   * pool before the caller sleeps, so a waiter holds neither a connection nor a transaction — and
   * so no snapshot — between attempts.
   */
  private AcquiredLock tryToLock(LockType lockType, String lockVersion) throws DbLockException {
    try {
      return JdbcHelper.inTransaction(jdbcTemplate, conn -> {
        int lockId = JdbcHelper.autoIncrementInsert(conn, SQL_INSERT_LOCK,
            lockType.getDbValue(), lockVersion, getHostName());
        return getLockDetails(conn, lockId, lockType, lockVersion);
      });
    } catch (SQLException e) {
      String sqlState = e.getSQLState();
      if (sqlState != null && sqlState.startsWith("23")) {
        log.debug("Lock {} is held by another instance (sqlState={}). Retrying...",
            lockType, sqlState);
        return null;
      }
      throw new DbLockException("Database error while acquiring lock for " + lockType, e);
    }
  }

  @Override
  public void releaseLock(AcquiredLock lock, boolean updateLatestLock) throws DbLockException {
    try {
      JdbcHelper.inTransaction(jdbcTemplate, conn -> release(conn, lock, updateLatestLock));
    } catch (SQLException e) {
      log.warn("Failed to release lock id={}, type={}", lock.getLockId(), lock.getLockType(), e);
      throw new DbLockException("Couldn't release lock", e);
    }
    cancelHoldTimeout(lock);
  }

  /** What a renewal found in the database. */
  private enum RenewOutcome { RENEWED, EXPIRED, GONE }

  /**
   * Renews in three steps. This instance's hold-timeout is claimed and stopped first, so it cannot
   * release the lock behind a renewal that succeeds; a hold-timeout that has already claimed the
   * lock is releasing it, and the renewal fails without touching the database. The database
   * renewal follows as one transaction, and the hold-timeout is re-armed for the full period only
   * once that has committed. When the database cannot be reached the hold-timeout is put back with
   * the time it had left, since the lease in the database is then unchanged.
   */
  @Override
  public void renewLock(AcquiredLock lock) throws DbLockException {
    LockType lockType = lock.getLockType();
    long holdTimeout = lockHelper.getLockHoldTimeout(lockType);
    HoldTimeout armed = holdTimeouts.remove(lock);
    if (armed == null) {
      throw new DbLockLostException(String.format("Cannot renew lock id=%d, type=%s: this "
          + "instance does not hold it — it was released, its lock-hold-timeout (%d ms) ran out, "
          + "or it was acquired by another instance.", lock.getLockId(), lockType, holdTimeout));
    }
    long remaining = armed.cancel();
    RenewOutcome outcome;
    try {
      outcome = JdbcHelper.inTransaction(jdbcTemplate, conn -> renew(conn, lock));
    } catch (SQLException e) {
      armHoldTimeout(lock, remaining);
      throw new DbLockException("Couldn't renew lock id=" + lock.getLockId() + ", type="
          + lockType, e);
    }
    switch (outcome) {
      case RENEWED -> {
        armHoldTimeout(lock, holdTimeout);
        log.debug("Renewed lock id = {}, type = {} for another {} ms.", lock.getLockId(),
            lockType, holdTimeout);
      }
      case EXPIRED -> throw new DbLockLostException(String.format("Cannot renew lock id=%d, "
          + "type=%s: its lease of %d ms had already expired, so it was released instead.",
          lock.getLockId(), lockType, holdTimeout));
      case GONE -> throw new DbLockLostException(String.format("Cannot renew lock id=%d, type=%s: "
          + "it is no longer held — released, or reclaimed as stale by another instance.",
          lock.getLockId(), lockType));
    }
  }

  private RenewOutcome renew(Connection conn, AcquiredLock lock) throws SQLException {
    LockType lockType = lock.getLockType();
    ExistingLock current = lockAndRead(conn, lock.getLockId(), lockType);
    if (current == null) {
      return RenewOutcome.GONE;
    }
    if (isExpired(current, lockType)) {
      log.warn("Lock id={}, type={} was renewed after its lease had expired ({}s ago, "
              + "holdTimeout={}ms); releasing it.", current.id(), lockType,
          secondsExpired(current, lockType), lockHelper.getLockHoldTimeout(lockType));
      moveToHistory(conn, current.id(), lockType.getDbValue());
      return RenewOutcome.EXPIRED;
    }
    JdbcHelper.executeUpdate(conn, ageQueries(conn).renewLock(), current.id());
    return RenewOutcome.RENEWED;
  }

  @Override
  public boolean hasLock(LockType lockType) {
    try {
      return JdbcHelper.inTransaction(jdbcTemplate,
          conn -> JdbcHelper.count(conn, SQL_LOCK_COUNT, lockType.getDbValue()) > 0);
    } catch (SQLException e) {
      log.warn("Ignoring SQLException on hasLock()", e);
      return false;
    }
  }

  /**
   * Waits for the next poll, or gives up when the thread is interrupted. Swallowing the interrupt
   * and re-setting the flag is not enough here: every later sleep would return at once and the
   * loop would hammer the database with failed inserts until the acquire timeout.
   */
  private void sleepUntilNextPoll(LockType lockType) throws DbLockException {
    try {
      Thread.sleep(lockHelper.getLockAcquirePollInterval(lockType));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DbLockException("Interrupted while waiting to acquire lock for " + lockType, e);
    }
  }

  /**
   * Attempts to reclaim the lock currently held for {@code lockType} when it has already exceeded
   * its configured lock-hold-timeout. Such a lock is almost certainly stale — left behind by a
   * holder that crashed, was OOM-killed, or hung — so instead of blocking until that holder
   * restarts, we release it here (recording it in history first) and let the caller re-attempt the
   * insert immediately. This is the on-demand counterpart to the startup-only
   * {@link #removeStaleLocks()}.
   *
   * <p>The delete is keyed on the specific lock id observed to be stale, never on {@code lock_type}
   * alone. That keeps the reclaim safe under contention: if another instance replaced the stale
   * lock with a fresh one between our read and our delete, the delete matches zero rows and the new
   * holder is left untouched.
   *
   * <p>It runs as its own short transaction, ended before the caller sleeps — including when there
   * is nothing to reclaim, the common case for a waiter.
   *
   * @return {@code true} if a stale lock row was reclaimed; {@code false} if there was no lock, it
   *     was not yet stale, another instance reclaimed it first, or the attempt failed.
   */
  private boolean reclaimStaleLock(LockType lockType) {
    try {
      return JdbcHelper.inTransaction(jdbcTemplate, conn -> reclaimIfStale(conn, lockType));
    } catch (SQLException e) {
      log.warn("Failed to reclaim stale lock for {}; will retry acquisition normally.",
          lockType, e);
      return false;
    }
  }

  private boolean reclaimIfStale(Connection conn, LockType lockType) throws SQLException {
    ExistingLock existing = fetchExistingLock(conn, lockType);
    if (existing == null || !isExpired(existing, lockType)) {
      return false;
    }
    return removeIfStillStale(conn, existing.id(), lockType, "Reclaiming stale lock");
  }

  /**
   * Removes a lock read as stale, after taking its row lock and reading it again: its holder may
   * have renewed it since it was read, and then it is not stale any more and stays.
   *
   * @return whether this call removed the lock row
   */
  private boolean removeIfStillStale(Connection conn, int lockId, LockType lockType,
      String action) throws SQLException {
    ExistingLock current = lockAndRead(conn, lockId, lockType);
    if (current == null || !isExpired(current, lockType)) {
      return false;
    }
    log.warn("{}: id={}, type={}, version={}, hostname={}, created_on={} (expired {}s ago, "
            + "holdTimeout={}ms)", action, current.id(), lockType, current.lockVersion(),
        current.hostname(), current.createdOn(), secondsExpired(current, lockType),
        lockHelper.getLockHoldTimeout(lockType));
    return moveToHistory(conn, lockId, lockType.getDbValue());
  }

  /**
   * Takes the row lock of the lock {@code lockId} (see {@link #SQL_LOCK_ROW}) and reads it as it
   * now stands, or {@code null} when it is gone.
   */
  private ExistingLock lockAndRead(Connection conn, int lockId, LockType lockType)
      throws SQLException {
    JdbcHelper.executeUpdate(conn, SQL_LOCK_ROW, lockId);
    AgeQueries queries = ageQueries(conn);
    try (PreparedStatement ps = conn.prepareStatement(queries.selectLockById())) {
      ps.setInt(1, lockId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        ExistingLock current = readLock(rs, rs.getString("lock_type").trim(), queries);
        return current.lockType().equals(lockType.getDbValue()) ? current : null;
      }
    }
  }

  private OffsetDateTime expiresAt(ExistingLock lock, LockType lockType) {
    return lock.createdOn().plus(lockHelper.getLockHoldTimeout(lockType), ChronoUnit.MILLIS);
  }

  private boolean isExpired(ExistingLock lock, LockType lockType) {
    return expiresAt(lock, lockType).isBefore(lock.now());
  }

  private long secondsExpired(ExistingLock lock, LockType lockType) {
    return ChronoUnit.SECONDS.between(expiresAt(lock, lockType), lock.now());
  }

  /**
   * Copies the lock row into the history and deletes it, keyed on its id. The copy is read from
   * the row, so it has to come first; when the delete then matches nothing, someone else — the
   * hold-timeout, another releaser, another instance's reclaim — removed the row in the meantime
   * and wrote the history row for it, so ours is rolled back rather than committed as a duplicate.
   * So is anything else the caller wrote in this transaction for a lock that was no longer there.
   *
   * @return whether this call removed the lock row
   */
  private static boolean moveToHistory(Connection conn, int lockId, String lockTypeValue)
      throws SQLException {
    JdbcHelper.executeUpdate(conn, SQL_INSERT_HISTORY, lockId);
    int deleted = JdbcHelper.executeUpdate(conn, SQL_DELETE_LOCK, lockId, lockTypeValue);
    if (deleted == 0) {
      conn.rollback();
      return false;
    }
    return true;
  }

  /**
   * Releases {@code lock} inside the caller's transaction: the latest-lock record first when asked
   * for, then the move to the history.
   *
   * @return whether this call released the lock; {@code false} when it was already gone
   */
  private boolean release(Connection conn, AcquiredLock lock, boolean updateLatestLock)
      throws SQLException {
    if (updateLatestLock) {
      handleLatestLock(conn, lock.getLockId(), lock.getLockType());
    }
    if (!moveToHistory(conn, lock.getLockId(), lock.getLockType().getDbValue())) {
      if (updateLatestLock) {
        log.warn("Lock id={}, type={} was no longer held when released: its lock-hold-timeout "
                + "({} ms) released it, or another instance reclaimed it as stale. The work done "
                + "under it is NOT recorded in DB_LOCK_LATEST.",
            lock.getLockId(), lock.getLockType(),
            lockHelper.getLockHoldTimeout(lock.getLockType()));
      } else {
        log.trace("releaseLock: No lock exists for lockId = {}, lockType = {}",
            lock.getLockId(), lock.getLockType());
      }
      return false;
    }
    log.info("Released lock after {} seconds. lockId = {}, and lockType = {}",
        ChronoUnit.SECONDS.between(lock.getLockAcquiredAt(), OffsetDateTime.now()),
        lock.getLockId(), lock.getLockType());
    return true;
  }

  private ExistingLock fetchExistingLock(Connection conn, LockType lockType) throws SQLException {
    AgeQueries queries = ageQueries(conn);
    try (PreparedStatement ps = conn.prepareStatement(queries.selectLockByType())) {
      ps.setString(1, lockType.getDbValue());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readLock(rs, lockType.getDbValue(), queries) : null;
      }
    }
  }

  private static ExistingLock readLock(ResultSet rs, String lockType, AgeQueries queries)
      throws SQLException {
    return new ExistingLock(
        rs.getInt("id"),
        lockType,
        rs.getString("lock_version"),
        rs.getString("hostname"),
        JdbcHelper.getOffsetDateTime(rs, "created_on"),
        queries.now(rs));
  }

  /** A lock row as read, with the time it was read at by the clock that judges its age. */
  private record ExistingLock(int id, String lockType, String lockVersion, String hostname,
      OffsetDateTime createdOn, OffsetDateTime now) {
  }

  /**
   * One daemon thread per service instance runs every hold-timeout. It is started up front, so
   * arming a hold-timeout never has to create a thread, and it neither keeps the JVM alive nor
   * outlives {@link #destroy()}, which cancels whatever is still pending.
   */
  private static ScheduledThreadPoolExecutor newHoldTimeoutScheduler() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
      Thread thread = new Thread(task, "db-lock-hold-timeout");
      thread.setDaemon(true);
      return thread;
    });
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    scheduler.prestartCoreThread();
    return scheduler;
  }

  /**
   * Arms the hold-timeout of a lock just committed. After {@link #destroy()} it can no longer be
   * armed, and a lock without one could only ever be freed by another instance's stale reclaim, so
   * the lock is given back at once and the acquire fails.
   */
  private void armHoldTimeout(AcquiredLock acquiredLock) throws DbLockException {
    armHoldTimeout(acquiredLock, lockHelper.getLockHoldTimeout(acquiredLock.getLockType()));
  }

  private void armHoldTimeout(AcquiredLock acquiredLock, long delayMillis)
      throws DbLockException {
    HoldTimeout holdTimeout = new HoldTimeout(acquiredLock);
    // Registered before it is scheduled, so that even a zero delay finds its own entry to claim.
    holdTimeouts.put(acquiredLock, holdTimeout);
    try {
      holdTimeout.future.set(
          holdTimeoutScheduler.schedule(holdTimeout, delayMillis, TimeUnit.MILLISECONDS));
    } catch (RejectedExecutionException e) {
      holdTimeouts.remove(acquiredLock, holdTimeout);
      try {
        releaseLock(acquiredLock, false);
      } catch (DbLockException releaseFailure) {
        e.addSuppressed(releaseFailure);
      }
      throw new DbLockException("The lock service has been shut down; the "
          + acquiredLock.getLockType() + " lock was given back.", e);
    }
  }

  /**
   * One armed hold-timeout. Its entry in {@link #holdTimeouts} is the claim on the lock: whoever
   * removes the entry — the hold-timeout when it fires, or a release or renewal by the holder — owns
   * what happens next, and the others stand down. Cancelling the future alone cannot decide that,
   * since a task that has already started still reports itself as cancellable.
   */
  private final class HoldTimeout implements Runnable {

    private final AcquiredLock lock;
    private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

    private HoldTimeout(AcquiredLock lock) {
      this.lock = lock;
    }

    @Override
    public void run() {
      // Spent once claimed, whatever the release does: past its hold-timeout the row is any
      // instance's to reclaim as stale.
      if (holdTimeouts.remove(lock, this)) {
        release();
      }
    }

    private void release() {
      log.warn("Releasing lock {}-{} because of timeout: {}", lock.getLockType(), lock.getLockId(),
          lockHelper.getLockHoldTimeout(lock.getLockType()));
      try {
        releaseLock(lock, false);
      } catch (DbLockException e) {
        log.warn("Ignoring exception during timer-based release of the lock record {}--{}.",
            lock.getLockType(), lock.getLockId(), e);
      }
    }

    /** Stops it; the caller has already claimed it by removing its entry. */
    private long cancel() {
      ScheduledFuture<?> scheduled = future.get();
      if (scheduled == null) {
        return 0;
      }
      long remaining = scheduled.getDelay(TimeUnit.MILLISECONDS);
      scheduled.cancel(false);
      return Math.max(0, remaining);
    }
  }

  private void cancelHoldTimeout(AcquiredLock acquiredLock) {
    HoldTimeout holdTimeout = holdTimeouts.remove(acquiredLock);
    if (holdTimeout != null) {
      log.trace("Cancelling auto lock release timer for lock id = {}, type = {}",
          acquiredLock.getLockId(), acquiredLock.getLockType());
      holdTimeout.cancel();
    }
  }

  private String getHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "N/A";
    }
  }

  private AcquiredLock getLockDetails(Connection conn, int lockId, LockType lockType,
      String lockVersion) throws SQLException {

    LatestLock latestLock = fetchLatestLock(conn, lockType);
    return AcquiredLock.of(lockId, lockType, lockVersion, latestLock);
  }

  /**
   * The latest version recorded for {@code lockType}, read together with the database's time so a
   * downgrade's waiting period is measured by the clock that stamped the record.
   */
  private LatestLock fetchLatestLock(Connection conn, LockType lockType) throws SQLException {
    AgeQueries queries = ageQueries(conn);
    try (PreparedStatement ps = conn.prepareStatement(queries.selectLatestLock())) {
      ps.setString(1, lockType.getDbValue());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next()
            ? new LatestLock(rs.getString("lock_version"),
                JdbcHelper.getOffsetDateTime(rs, "lock_acquired_on"), queries.readAt(rs))
            : null;
      }
    }
  }

  private void handleLatestLock(Connection conn, int lockId, LockType lockType)
      throws SQLException {
    int n = JdbcHelper.count(conn, SQL_LATEST_LOCK_COUNT, lockType.getDbValue());
    if (n == 0) {
      insertLatestLock(conn, lockId);
    } else {
      updateLatestLock(conn, lockId);
    }
  }

  private void insertLatestLock(Connection conn, int lockId) throws SQLException {
    int count = JdbcHelper.executeUpdate(conn, SQL_INSERT_LATEST_LOCK, lockId);
    if (count == 0) {
      log.warn("Could not initialize LatestLock using lockId = {}", lockId);
    } else {
      log.trace("Initialized LatestLock using lockId = {}", lockId);
    }
  }

  private void updateLatestLock(Connection conn, int lockId) throws SQLException {
    int count;
    try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_LATEST_LOCK)) {
      for (int i = 1; i <= 4; i++) {
        ps.setInt(i, lockId);
      }
      count = ps.executeUpdate();
    }
    if (count == 0) {
      log.warn("Could not update LatestLock using lockId = {}", lockId);
    } else {
      log.trace("Updated LatestLock using lockId = {}", lockId);
    }
  }

  @Override
  public void destroy() {
    log.info("Destroying DbLockService");
    holdTimeoutScheduler.shutdown();
    for (AcquiredLock acquiredLock : new ArrayList<>(holdTimeouts.keySet())) {
      log.warn("Releasing still active {}", acquiredLock);
      try {
        releaseLock(acquiredLock, false);
      } catch (DbLockException | RuntimeException e) {
        log.warn("Ignoring exception while trying to release {}", acquiredLock, e);
      }
    }
  }

  /**
   * Removes stale locks that were left behind due to non-graceful shutdowns (e.g. kill -9,
   * OOM kill, node eviction). A lock is considered stale if it has been held longer than the
   * configured lock-hold-timeout for its type. Intended to be called once at startup.
   */
  public void removeStaleLocks() {
    List<ExistingLock> locks;
    try {
      locks = JdbcHelper.inTransaction(jdbcTemplate, this::fetchAllLocks);
    } catch (SQLException e) {
      log.warn("Failed to clean up stale locks on startup", e);
      return;
    }
    for (ExistingLock existing : locks) {
      LockType lockType = LockType.fromDbValue(existing.lockType());
      if (lockType == null) {
        log.warn("Skipping lock id={} with unknown lock_type '{}'",
            existing.id(), existing.lockType());
        continue;
      }
      if (isExpired(existing, lockType)) {
        try {
          // Each stale row is moved in its own transaction, so a row another instance removed
          // in the meantime rolls back only its own history copy, and a row its holder renewed in
          // the meantime is left alone.
          JdbcHelper.inTransaction(jdbcTemplate,
              conn -> removeIfStillStale(conn, existing.id(), lockType, "Removing stale lock"));
        } catch (SQLException e) {
          log.warn("Failed to remove stale lock id={} on startup", existing.id(), e);
        }
      }
    }
  }

  private List<ExistingLock> fetchAllLocks(Connection conn) throws SQLException {
    AgeQueries queries = ageQueries(conn);
    List<ExistingLock> locks = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(queries.selectAllLocks());
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        locks.add(readLock(rs, rs.getString("lock_type").trim(), queries));
      }
    }
    return locks;
  }
}
