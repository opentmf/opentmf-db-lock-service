package org.opentmf.db.lock.service.impl;


import org.opentmf.db.lock.config.DbLockProperties;
import org.opentmf.db.lock.exception.DbLockException;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Timer;
import java.util.TimerTask;
import lombok.RequiredArgsConstructor;
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

  public DbLockServiceImpl(JdbcTemplate jdbcTemplate, DbLockProperties dbLockProperties) {
    this.jdbcTemplate = jdbcTemplate;
    lockHelper = new DurationHelper(dbLockProperties);
  }

  private final Map<AcquiredLock, Timer> timerMap = new ConcurrentHashMap<>();

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

  private static final String SQL_GET_LATEST_LOCK =
      "select lock_version, lock_acquired_on from DB_LOCK_LATEST where lock_type = ?";

  private static final String SQL_INSERT_HISTORY =
      "insert into DB_LOCK_HISTORY (lock_id, lock_type, lock_version, hostname, lock_acquired_on) "
          + "select id, lock_type, lock_version, hostname, created_on "
          + "from DB_LOCK "
          + "where DB_LOCK.id = ?";

  private static final String SQL_SELECT_ALL_LOCKS =
      "select id, lock_type, lock_version, hostname, created_on from DB_LOCK";

  private static final String SQL_SELECT_LOCK_BY_TYPE =
      "select id, lock_version, hostname, created_on from DB_LOCK where lock_type = ?";

  @Override
  public AcquiredLock acquireLock(LockType lockType, String lockVersion) throws DbLockException {
    log.debug("Attempting to acquire lock for lockType = {}, and lockVersion = {}",
        lockType, lockVersion);
    long t0 = System.currentTimeMillis();
    while (true) {
      AcquiredLock acquiredLock = tryToLock(lockType, lockVersion);
      if (acquiredLock != null) {
        createLockReleaseTimer(acquiredLock);
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
      JdbcHelper.inTransaction(jdbcTemplate, conn -> {
        if (updateLatestLock) {
          handleLatestLock(conn, lock.getLockId(), lock.getLockType());
        }
        insertHistoryRecord(conn, lock.getLockId());
        deleteLockRecordIfExists(conn, lock);
        return null;
      });
    } catch (SQLException e) {
      log.warn("Failed to release lock id={}, type={}", lock.getLockId(), lock.getLockType(), e);
      throw new DbLockException("Couldn't release lock", e);
    }
    cancelAndRemoveLockReleaseTimer(lock);
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

  private void sleepUntilNextPoll(LockType lockType) {
    try {
      Thread.sleep(lockHelper.getLockAcquirePollInterval(lockType));
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
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
    if (existing == null) {
      return false;
    }
    long holdTimeout = lockHelper.getLockHoldTimeout(lockType);
    OffsetDateTime expiresAt = existing.createdOn().plus(holdTimeout, ChronoUnit.MILLIS);
    if (!expiresAt.isBefore(OffsetDateTime.now())) {
      return false;
    }
    long staleSecs = ChronoUnit.SECONDS.between(expiresAt, OffsetDateTime.now());
    log.warn("Reclaiming stale lock before acquiring {}: id={}, version={}, hostname={}, "
            + "created_on={} (expired {}s ago, holdTimeout={}ms)",
        lockType, existing.id(), existing.lockVersion(), existing.hostname(),
        existing.createdOn(), staleSecs, holdTimeout);
    JdbcHelper.executeUpdate(conn, SQL_INSERT_HISTORY, existing.id());
    int deleted = JdbcHelper.executeUpdate(conn, SQL_DELETE_LOCK, existing.id(),
        lockType.getDbValue());
    return deleted > 0;
  }

  private ExistingLock fetchExistingLock(Connection conn, LockType lockType) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_LOCK_BY_TYPE)) {
      ps.setString(1, lockType.getDbValue());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return new ExistingLock(
            rs.getInt("id"),
            rs.getString("lock_version"),
            rs.getString("hostname"),
            rs.getObject("created_on", OffsetDateTime.class));
      }
    }
  }

  private record ExistingLock(
      int id, String lockVersion, String hostname, OffsetDateTime createdOn) {
  }

  private void createLockReleaseTimer(AcquiredLock acquiredLock) {
    Timer timer = new Timer();
    timer.schedule(new DbLockCancelTimer(acquiredLock),
        lockHelper.getLockHoldTimeout(acquiredLock.getLockType()));
    timerMap.put(acquiredLock, timer);
  }

  private synchronized void cancelAndRemoveLockReleaseTimer(AcquiredLock acquiredLock) {
    Timer timer = timerMap.get(acquiredLock);
    if (timer != null) {
      log.trace("Cancelling auto lock release timer for lock id = {}, type = {}",
          acquiredLock.getLockId(), acquiredLock.getLockType());
      timer.cancel();
      timerMap.remove(acquiredLock);
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

    LatestLock latestLock = JdbcHelper.getLatestLock(conn, SQL_GET_LATEST_LOCK,
        lockType.getDbValue());
    return AcquiredLock.of(lockId, lockType, lockVersion, latestLock);
  }

  private void deleteLockRecordIfExists(Connection conn, AcquiredLock lock)
      throws SQLException {
    int count = JdbcHelper.executeUpdate(conn, SQL_DELETE_LOCK, lock.getLockId(),
        lock.getLockType().getDbValue());
    if (count == 0) {
      log.trace("releaseLock: No lock exists for lockId = {}, lockType = {}",
          lock.getLockId(), lock.getLockType());
    } else {
      log.info("Released lock after {} seconds. lockId = {}, and lockType = {}",
          ChronoUnit.SECONDS.between(lock.getLockAcquiredAt(), OffsetDateTime.now()),
              lock.getLockId(), lock.getLockType());
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

  private void insertHistoryRecord(Connection conn, int lockId) throws SQLException {
    int count = JdbcHelper.executeUpdate(conn, SQL_INSERT_HISTORY, lockId);
    if (count == 0) {
      log.trace("insertHistoryRecord: No lock exists for lockId = {}", lockId);
    }
  }

  @Override
  public void destroy() {
    log.info("Destroying DbLockService");
    var locks = new ArrayList<>(timerMap.keySet());
    for (var acquiredLock : locks) {
      log.warn("Releasing still active {}", acquiredLock);
      try {
        releaseLock(acquiredLock, false);
      } catch (DbLockException e) {
        log.warn("Ignoring DbLockException while trying to release {}", acquiredLock, e);
      }
    }
  }

  /**
   * Removes stale locks that were left behind due to non-graceful shutdowns (e.g. kill -9,
   * OOM kill, node eviction). A lock is considered stale if it has been held longer than the
   * configured lock-hold-timeout for its type. Intended to be called once at startup.
   */
  public void removeStaleLocks() {
    try {
      JdbcHelper.inTransaction(jdbcTemplate, conn -> {
        removeStaleLocks(conn);
        return null;
      });
    } catch (SQLException e) {
      log.warn("Failed to clean up stale locks on startup", e);
    }
  }

  private void removeStaleLocks(Connection conn) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_ALL_LOCKS);
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        int lockId = rs.getInt("id");
        String lockTypeValue = rs.getString("lock_type").trim();
        String lockVersion = rs.getString("lock_version");
        String hostname = rs.getString("hostname");
        OffsetDateTime createdOn = rs.getObject("created_on", OffsetDateTime.class);

        LockType lockType = LockType.fromDbValue(lockTypeValue);
        if (lockType == null) {
          log.warn("Skipping lock id={} with unknown lock_type '{}'", lockId, lockTypeValue);
          continue;
        }

        long holdTimeout = lockHelper.getLockHoldTimeout(lockType);
        OffsetDateTime expiresAt = createdOn.plus(holdTimeout, ChronoUnit.MILLIS);
        if (expiresAt.isBefore(OffsetDateTime.now())) {
          long staleSecs = ChronoUnit.SECONDS.between(expiresAt, OffsetDateTime.now());
          log.warn("Removing stale lock: id={}, type={}, version={}, hostname={}, "
                  + "created_on={} (expired {}s ago, holdTimeout={}ms)",
              lockId, lockType, lockVersion, hostname, createdOn, staleSecs, holdTimeout);
          JdbcHelper.executeUpdate(conn, SQL_INSERT_HISTORY, lockId);
          JdbcHelper.executeUpdate(conn, SQL_DELETE_LOCK, lockId, lockTypeValue);
        }
      }
    }
  }

  @RequiredArgsConstructor
  private class DbLockCancelTimer extends TimerTask {

    private final AcquiredLock acquiredLock;

    @Override
    public void run() {
      log.warn(
          "Releasing lock {}-{} because of timeout: {}",
          acquiredLock.getLockType(),
          acquiredLock.getLockId(),
          lockHelper.getLockHoldTimeout(acquiredLock.getLockType()));
      try {
        releaseLock(acquiredLock, false);
      } catch (DbLockException e) {
        log.warn("Ignoring exception during timer-based release of the lock record {}--{}.",
            acquiredLock.getLockType(), acquiredLock.getLockId(), e);
      }
    }
  }
}
