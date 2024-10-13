package com.pia.db.lock.service;

import com.pia.db.lock.model.LockType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.OffsetDateTime;

@Service
public class AnnotatedTestPersistenceService {

  @Autowired private JdbcTemplate jdbcTemplate;

  public int historyCount(LockType lockType) {
    return jdbcTemplate.queryForObject(
        "select count(*) from DB_LOCK_HISTORY where lock_type = ?",
        Integer.class,
        lockType.getDbValue());
  }

  public boolean latestLockExists(LockType lockType) {
    return jdbcTemplate.queryForObject(
            "select count(*) from DB_LOCK_LATEST where lock_type = ?",
            Integer.class,
            lockType.getDbValue())
        == 1;
  }

  public int insertLock(LockType lockType, String version) {
    return jdbcTemplate.update(
        "insert into DB_LOCK (lock_type, lock_version, hostname) values (?, ?, ?)",
        lockType.getDbValue(),
        version,
        "localhost");
  }

  public int insertLockLatest(LockType lockType, String version, OffsetDateTime time) {
    return jdbcTemplate.update(
        "insert into DB_LOCK_LATEST (lock_type, lock_version, hostname, lock_acquired_on) values (?, ?, ?, ?)",
        lockType.getDbValue(),
        version,
        "localhost",
        time);
  }

  public String getLatestLockVersion(LockType lockType) {
    return jdbcTemplate.queryForObject(
        "select lock_version from DB_LOCK_LATEST where lock_type = ?",
        String.class,
        lockType.getDbValue());
  }

  public void closeConnection() throws SQLException {
    jdbcTemplate.getDataSource().getConnection().close();
  }

  public void deleteLocks(LockType lockType) {
    deleteLock("DB_LOCK", lockType);
    deleteLock("DB_LOCK_HISTORY", lockType);
    deleteLock("DB_LOCK_LATEST", lockType);
  }

  public void deleteLock(String tableName, LockType lockType) {
    jdbcTemplate.update("delete from " + tableName + " where lock_type = ?", lockType.getDbValue());
  }
}
