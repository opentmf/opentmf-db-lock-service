package org.opentmf.db.lock.service.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentmf.db.lock.exception.DbLockException;
import org.opentmf.db.lock.exception.DbLockLostException;
import org.opentmf.db.lock.model.AcquiredLock;
import org.opentmf.db.lock.model.LockType;

/**
 * The interface stays source and binary compatible with implementations written before 3.1.0.
 *
 * @author Gokhan Demir
 */
class DbLockServiceTest {

  /**
   * An implementation written against the 3.0.0 interface: it has no {@code renewLock}. It must
   * still compile, and a renewal through it must refuse loudly rather than let the caller believe
   * its lease was extended.
   */
  private static final class ImplementationBefore310 implements DbLockService {

    @Override
    public AcquiredLock acquireLock(LockType lockType, String lockVersion) {
      return AcquiredLock.of(7, lockType, lockVersion, null);
    }

    @Override
    public void releaseLock(AcquiredLock lock, boolean updateLatestLock) {
      // nothing held
    }

    @Override
    public boolean hasLock(LockType lockType) {
      return false;
    }
  }

  @Test
  void renewLock_onAnImplementationWrittenBefore310_refusesAsALostLock() throws DbLockException {
    DbLockService service = new ImplementationBefore310();
    AcquiredLock lock = service.acquireLock(LockType.LOCK_X, "1");

    DbLockLostException refusal =
        assertThrows(DbLockLostException.class, () -> service.renewLock(lock));

    String message = refusal.getMessage();
    assertTrue(message.contains("id=7"), message);
    assertTrue(message.contains("type=LOCK_X"), message);
    assertTrue(message.contains(ImplementationBefore310.class.getName()), message);
    assertTrue(message.contains("does not support renewLock"), message);
  }
}
