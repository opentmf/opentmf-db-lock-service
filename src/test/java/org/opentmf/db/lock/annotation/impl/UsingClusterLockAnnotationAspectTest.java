package org.opentmf.db.lock.annotation.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.db.lock.annotation.UsingClusterLock;
import org.opentmf.db.lock.exception.DbLockException;
import org.opentmf.db.lock.model.AcquiredLock;
import org.opentmf.db.lock.model.LockType;
import org.opentmf.db.lock.service.api.DbLockService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.core.env.Environment;

/**
 * Covers the aspect's failure paths, which {@code AnnotatedTestServiceIT} cannot reach because it
 * exercises a real, healthy {@link DbLockService}: a release that itself fails while another
 * exception is propagating, and an {@link Error} escaping the advised method.
 *
 * @author Gokhan Demir
 */
class UsingClusterLockAnnotationAspectTest {

  private DbLockService dbLockService;
  private UsingClusterLockAnnotationAspect aspect;
  private ProceedingJoinPoint joinPoint;
  private UsingClusterLock annotation;
  private AcquiredLock lock;

  @BeforeEach
  void setUp() throws Throwable {
    Environment environment = mock(Environment.class);
    dbLockService = mock(DbLockService.class);
    aspect = new UsingClusterLockAnnotationAspect(environment, dbLockService);

    joinPoint = mock(ProceedingJoinPoint.class);
    when(joinPoint.getArgs()).thenReturn(new Object[0]);

    annotation = mock(UsingClusterLock.class);
    when(annotation.requestedVersion()).thenReturn("1.0");
    when(annotation.downgradeAllowedAfter()).thenReturn("PT1S");
    when(annotation.lockType()).thenReturn(LockType.LOCK_X);
    when(annotation.failureMessage()).thenReturn("");
    when(annotation.executeOnSameVersion()).thenReturn(true);

    lock = AcquiredLock.of(1, LockType.LOCK_X, "1.0", null);
    when(dbLockService.acquireLock(any(), any())).thenReturn(lock);
  }

  @Test
  void whenTheMethodFails_aFailingReleaseIsAttachedAsSuppressed_notSwallowed() throws Throwable {
    IllegalStateException primary = new IllegalStateException("business logic blew up");
    when(joinPoint.proceed(any())).thenThrow(primary);
    DbLockException releaseFailure = new DbLockException("could not release");
    doThrow(releaseFailure).when(dbLockService).releaseLock(lock, false);

    Exception thrown =
        assertThrows(Exception.class, () -> aspect.wrapWithLock(joinPoint, annotation));

    assertSame(primary, thrown, "the business failure must stay the primary exception");
    assertArrayEquals(new Throwable[] {releaseFailure}, thrown.getSuppressed(),
        "a failed release must be visible, not silently dropped");
  }

  @Test
  void whenTheMethodFails_andAFailureMessageIsConfigured_itWrapsTheCause() throws Throwable {
    when(annotation.failureMessage()).thenReturn("sync failed");
    IllegalStateException primary = new IllegalStateException("business logic blew up");
    when(joinPoint.proceed(any())).thenThrow(primary);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> aspect.wrapWithLock(joinPoint, annotation));

    assertEquals("sync failed", thrown.getMessage());
    assertSame(primary, thrown.getCause());
    verify(dbLockService).releaseLock(lock, false);
  }

  /**
   * The catch clause only handles {@link Exception}. Without the {@code finally} net an
   * {@link Error} would leave the lock held until its hold-timeout expired.
   */
  @Test
  void whenTheMethodThrowsAnError_theFinallyNetStillReleasesTheLock() throws Throwable {
    StackOverflowError error = new StackOverflowError("deep recursion");
    when(joinPoint.proceed(any())).thenThrow(error);

    StackOverflowError thrown =
        assertThrows(StackOverflowError.class, () -> aspect.wrapWithLock(joinPoint, annotation));

    assertSame(error, thrown);
    verify(dbLockService).releaseLock(lock, false);
  }

  @Test
  void whenTheErrorPathReleaseAlsoFails_theOriginalErrorStillPropagates() throws Throwable {
    StackOverflowError error = new StackOverflowError("deep recursion");
    when(joinPoint.proceed(any())).thenThrow(error);
    doThrow(new DbLockException("release failed")).when(dbLockService)
        .releaseLock(any(), anyBoolean());

    StackOverflowError thrown =
        assertThrows(StackOverflowError.class, () -> aspect.wrapWithLock(joinPoint, annotation));

    assertSame(error, thrown, "best-effort cleanup must not replace the original Error");
  }
}
