begin transaction;

/*==============================================================*/
/* Table: DB_LOCK                                               */
/*==============================================================*/
create table if not exists DB_LOCK (
   id                   SERIAL               not null,
   lock_type            CHAR(1)              not null
      constraint CKC_LOCK_TYPE_DB_LOCK check (lock_type in ('B','C','X','Y','Z')),
   lock_version         VARCHAR(50)          not null,
   hostname             VARCHAR(255)         not null,
   created_on           TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
   constraint PK_DB_LOCK primary key (id)
);

comment on table DB_LOCK is
'Current locks are kept in this table.';

comment on column DB_LOCK.id is
'auto incremented id, to be used when releasing the lock when the lock hold timeout is reached.';

comment on column DB_LOCK.lock_type is
'lock_type, can be something like bpmn or catalog';

comment on column DB_LOCK.lock_version is
'the version of the lock';

comment on column DB_LOCK.hostname is
'the hostname that initiated this lock.';

comment on column DB_LOCK.created_on is
'record created at this date time.';

/*==============================================================*/
/* Index: NDX_LOCK_TYPE                                         */
/*==============================================================*/
create unique index if not exists NDX_LOCK_TYPE on DB_LOCK (
lock_type
);

/*==============================================================*/
/* Table: DB_LOCK_HISTORY                                       */
/*==============================================================*/
create table if not exists DB_LOCK_HISTORY (
   id                   SERIAL               not null,
   lock_id              INT4                 not null,
   lock_type            CHAR(1)              not null
      constraint CKC_LOCK_TYPE_DB_LOCK_ check (lock_type in ('B','C','X','Y','Z')),
   lock_version         VARCHAR(50)          not null,
   hostname             VARCHAR(255)         not null,
   lock_acquired_on     TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
   lock_released_on     TIMESTAMP WITH TIME ZONE not null default CURRENT_TIMESTAMP,
   constraint PK_DB_LOCK_HISTORY primary key (id)
);

comment on table DB_LOCK_HISTORY is
'Keeps the lock history for informational purposes.';

comment on column DB_LOCK_HISTORY.id is
'the auto incremented primary key for this history table.';

comment on column DB_LOCK_HISTORY.lock_id is
'the original lock id.';

comment on column DB_LOCK_HISTORY.lock_type is
'lock_type, can be something like bpmn or catalog';

comment on column DB_LOCK_HISTORY.lock_version is
'the version of the lock';

comment on column DB_LOCK_HISTORY.hostname is
'the hostname that initiated this lock.';

comment on column DB_LOCK_HISTORY.lock_acquired_on is
'the record lock was initiated at this datet ime.';

comment on column DB_LOCK_HISTORY.lock_released_on is
'record created at this date time.';

/*==============================================================*/
/* Index: NDX_DATE                                              */
/*==============================================================*/
create  index if not exists NDX_DATE on DB_LOCK_HISTORY (
lock_acquired_on
);

/*==============================================================*/
/* Index: NDX_LOCK                                              */
/*==============================================================*/
create  index if not exists NDX_LOCK on DB_LOCK_HISTORY (
lock_type,
lock_version
);

/*==============================================================*/
/* Table: DB_LOCK_LATEST                                        */
/*==============================================================*/
create table if not exists DB_LOCK_LATEST (
   lock_type            CHAR(1)              not null
      constraint CKC_LOCK_TYPE_DB_LOCK_ check (lock_type in ('B','C','X','Y','Z')),
   lock_version         VARCHAR(50)          not null,
   hostname             VARCHAR(255)         not null,
   lock_acquired_on     TIMESTAMP WITH TIME ZONE not null,
   constraint PK_DB_LOCK_LATEST primary key (lock_type)
);

comment on table DB_LOCK_LATEST is
'Latest applied version of a lock per lock_type.';

comment on column DB_LOCK_LATEST.lock_type is
'lock_type, can be something like bpmn or catalog';

comment on column DB_LOCK_LATEST.lock_version is
'the version of the lock';

comment on column DB_LOCK_LATEST.hostname is
'the hostname that initiated this lock.';

comment on column DB_LOCK_LATEST.lock_acquired_on is
'The real lock was acquired at this datetime.';

/*==============================================================*/
/* Backward-compat: widen lock_version on installs created pre-2.0.0
   (original CREATE TABLE used VARCHAR(10)). Fresh installs already
   declare VARCHAR(50) in the CREATE TABLE statements above.

   Each widening is guarded so the ALTER runs only when the column is
   still narrower than VARCHAR(50). This makes the migration idempotent:
   on an already-migrated (or fresh) schema the guard skips the ALTER,
   so no ACCESS EXCLUSIVE table lock is taken on every application start.
   Re-taking that exclusive lock on every boot could otherwise form a
   lock cycle (deadlock) when several application contexts share one
   database and start concurrently. Widening never truncates, so the
   one-time migration of a legacy VARCHAR(10) column is preserved. */
/*==============================================================*/
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_name = 'db_lock' AND column_name = 'lock_version'
               AND character_maximum_length IS DISTINCT FROM 50) THEN
    ALTER TABLE DB_LOCK ALTER COLUMN lock_version TYPE VARCHAR(50);
  END IF;
END $$;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_name = 'db_lock_history' AND column_name = 'lock_version'
               AND character_maximum_length IS DISTINCT FROM 50) THEN
    ALTER TABLE DB_LOCK_HISTORY ALTER COLUMN lock_version TYPE VARCHAR(50);
  END IF;
END $$;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_name = 'db_lock_latest' AND column_name = 'lock_version'
               AND character_maximum_length IS DISTINCT FROM 50) THEN
    ALTER TABLE DB_LOCK_LATEST ALTER COLUMN lock_version TYPE VARCHAR(50);
  END IF;
END $$;

commit transaction;