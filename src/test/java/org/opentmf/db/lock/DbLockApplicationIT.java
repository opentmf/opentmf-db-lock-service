package org.opentmf.db.lock;

import org.opentmf.db.lock.service.api.DbLockService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * @author Gokhan Demir
 */
@SpringBootTest
class DbLockApplicationIT {

  @Autowired private DbLockService dbLockService;

  @Test
  void contextLoads() {
    Assertions.assertNotNull(dbLockService);
  }
}
