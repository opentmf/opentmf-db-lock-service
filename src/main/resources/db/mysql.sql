-- MySQL / MariaDB creation script for opentmf-db-lock-service.
-- Targets MySQL 8.0.16+ (CHECK constraints enforced) and MariaDB 10.2+.
--
-- Note on timestamps: MySQL has no TIMESTAMP WITH TIME ZONE. This script uses
-- plain TIMESTAMP, which MySQL stores as UTC internally and converts to the
-- session time zone on read. For correct behaviour, run the application JVM
-- and the MySQL server with time_zone = UTC, otherwise the values returned
-- for created_on / lock_acquired_on will drift relative to OffsetDateTime.

create table if not exists DB_LOCK (
   id                   INT                  not null auto_increment,
   lock_type            CHAR(1)              not null,
   lock_version         VARCHAR(50)          not null,
   hostname             VARCHAR(255)         not null,
   created_on           TIMESTAMP            not null default CURRENT_TIMESTAMP,
   constraint PK_DB_LOCK primary key (id),
   constraint CKC_LOCK_TYPE_DB_LOCK check (lock_type in ('B','C','X','Y','Z')),
   constraint NDX_LOCK_TYPE unique (lock_type)
);

create table if not exists DB_LOCK_HISTORY (
   id                   INT                  not null auto_increment,
   lock_id              INT                  not null,
   lock_type            CHAR(1)              not null,
   lock_version         VARCHAR(50)          not null,
   hostname             VARCHAR(255)         not null,
   lock_acquired_on     TIMESTAMP            not null default CURRENT_TIMESTAMP,
   lock_released_on     TIMESTAMP            not null default CURRENT_TIMESTAMP,
   constraint PK_DB_LOCK_HISTORY primary key (id),
   constraint CKC_LOCK_TYPE_DB_LOCK_H check (lock_type in ('B','C','X','Y','Z')),
   INDEX NDX_DATE (lock_acquired_on),
   INDEX NDX_LOCK (lock_type, lock_version)
);

create table if not exists DB_LOCK_LATEST (
   lock_type            CHAR(1)              not null,
   lock_version         VARCHAR(50)          not null,
   hostname             VARCHAR(255)         not null,
   lock_acquired_on     TIMESTAMP            not null,
   constraint PK_DB_LOCK_LATEST primary key (lock_type),
   constraint CKC_LOCK_TYPE_DB_LOCK_L check (lock_type in ('B','C','X','Y','Z'))
);
