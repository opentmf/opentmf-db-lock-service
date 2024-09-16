package com.pia.db.lock.service.impl;


import com.pia.db.lock.config.DbLockProperties;
import com.pia.db.lock.model.AcquiredLock;
import com.pia.db.lock.model.LatestLock;
import com.pia.db.lock.model.LockType;
import com.pia.db.lock.util.JdbcHelper;
import com.pia.db.lock.exception.DbLockException;
import com.pia.db.lock.exception.DbLockTimeoutException;
import com.pia.db.lock.service.api.DbLockService;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Gokhan Demir
 */
@RequiredArgsConstructor
@Slf4j
public class DbLockServiceImpl implements DbLockService, DisposableBean {

  private final JdbcTemplate jdbcTemplate;
  private final DbLockProperties dbLockProperties;

  private final Map<AcquiredLock, Timer> timerMap = new HashMap<>();

  private static final String SQL_LATEST_LOCK_COUNT =
      "select count(*) from DB_LOCK_LATEST where lock_type = ?";

  private static final String SQL_INSERT_LATEST_LOCK =
      "insert into DB_LOCK_LATEST (lock_type, lock_version, hostname, lock_acquired_on) "
          + "select lock_type, lock_version, hostname, created_on from DB_LOCK "
          + "where id = ?";

  private static final String SQL_UPDATE_LATEST_LOCK = "update DB_LOCK_LATEST T "
      + "set lock_version = L.lock_version, hostname = L.hostname, lock_acquired_on = L.created_on "
      + "from DB_LOCK L "
      + "where L.id = ? and T.lock_type = L.lock_type";

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

  @Override
  public AcquiredLock acquireLock(LockType lockType, String lockVersion) throws DbLockException {
    Connection conn = null;
    log.debug("Attempting to acquire lock for lockType = {}, and lockVersion = {}",
        lockType, lockVersion);
    try {
      conn = JdbcHelper.getConnection(jdbcTemplate);
      int lockId = lock(conn, lockType, lockVersion);
      AcquiredLock acquiredLock = getLockDetails(conn, lockId, lockType);
      JdbcHelper.commit(conn);
      createLockReleaseTimer(acquiredLock);
      log.debug("Acquired lock id = {} for lockType = {}, and lockVersion = {}.",
          lockId, lockType, lockVersion);
      return acquiredLock;
    } catch (SQLException e) {
      JdbcHelper.rollback(conn);
      throw new DbLockException("", e);
    } finally {
      JdbcHelper.close(conn);
    }
  }

  @Override
  public void releaseLock(AcquiredLock lock, boolean updateLatestLock) throws DbLockException {
    Connection conn = null;
    try {
      conn = JdbcHelper.getConnection(jdbcTemplate);
      if (updateLatestLock) {
        handleLatestLock(conn, lock.getLockId(), lock.getLockType());
      }
      insertHistoryRecord(conn, lock.getLockId());
      deleteLockRecordIfExists(conn, lock);
      JdbcHelper.commit(conn);
      cancelAndRemoveLockReleaseTimer(lock);
    } catch (SQLException e) {
      JdbcHelper.rollback(conn);
      log.error("", e);
      throw new DbLockException("Couldn't release lock", e);
    } finally {
      JdbcHelper.close(conn);
    }
  }

  private int lock(Connection conn, LockType lockType, String lockVersion)
      throws DbLockException {
    long t0 = System.currentTimeMillis();
    while (true) {
      try {
        return JdbcHelper.autoIncrementInsert(conn, SQL_INSERT_LOCK,
            lockType.getDbValue(), lockVersion, getHostName());
      } catch (SQLException e) {
        if ((System.currentTimeMillis() - t0) > dbLockProperties.getLockAcquireTimeout()) {
          String message = String.format("Cannot acquire DB Lock for %s within the configured " +
              "%d millis. Giving up.", lockType, dbLockProperties.getLockAcquireTimeout());
          throw new DbLockTimeoutException(message);
        }
        log.debug("Sleeping {} milliseconds for the existing {} lock to be released.",
            dbLockProperties.getLockAcquirePollInterval(), lockType);
        sleepUntilNextPoll();
      }
    }
  }

  private void sleepUntilNextPoll() {
    try {
      Thread.sleep(dbLockProperties.getLockAcquirePollInterval());
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private void createLockReleaseTimer(AcquiredLock acquiredLock) {
    Timer timer = new Timer();
    timer.schedule(new DbLockCancelTimer(acquiredLock), dbLockProperties.getLockHoldTimeout());
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

  private AcquiredLock getLockDetails(Connection conn, int lockId, LockType lockType)
      throws SQLException {
    LatestLock latestLock = JdbcHelper.getLatestLock(conn, SQL_GET_LATEST_LOCK,
        lockType.getDbValue());
    return new AcquiredLock(lockId, lockType, 
        latestLock.getLockVersion(), latestLock.getLockReleasedAt());
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
    int count = JdbcHelper.executeUpdate(conn, SQL_UPDATE_LATEST_LOCK, lockId);
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
    for (Iterator<AcquiredLock> iterator = timerMap.keySet().iterator(); iterator.hasNext();) {
      var acquiredLock = iterator.next();
      log.warn("Releasing still active {}", acquiredLock);
      try {
        releaseLock(acquiredLock, false);
      } catch (DbLockException e) {
        log.warn("Ignoring DbLockException while trying to release {}", acquiredLock, e);
      }
    }
  }

  @RequiredArgsConstructor
  private class DbLockCancelTimer extends TimerTask {

    private final AcquiredLock acquiredLock;

    @Override
    @Transactional
    public void run() {
      log.warn(
          "Releasing lock {}-{} because of timeout: {}",
          acquiredLock.getLockType(),
          acquiredLock.getLockId(),
          dbLockProperties.getLockHoldTimeout());
      try {
        releaseLock(acquiredLock, false);
      } catch (DbLockException e) {
        log.warn("Ignoring exception during timer-based release of the lock record {}--{}.",
            acquiredLock.getLockType(), acquiredLock.getLockId(), e);
      }
    }
  }
}
