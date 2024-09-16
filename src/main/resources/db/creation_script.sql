/*==============================================================*/
/* Table: DB_LOCK                                               */
/*==============================================================*/
create table if not exists DB_LOCK (
   id                   SERIAL               not null,
   lock_type            CHAR(1)              not null
      constraint CKC_LOCK_TYPE_DB_LOCK check (lock_type in ('B','C','X','Y','Z')),
   lock_version         VARCHAR(10)          not null,
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
   lock_version         VARCHAR(10)          not null,
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
   lock_version         VARCHAR(10)          not null,
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
