-- Microsoft SQL Server creation script for opentmf-db-lock-service.
-- Targets SQL Server 2016+ (DATETIMEOFFSET available since 2008; IF OBJECT_ID
-- guards used instead of CREATE TABLE IF NOT EXISTS for broader compatibility).
--
-- Each guarded statement is a SINGLE T-SQL statement (no BEGIN/END batches),
-- so it survives Spring's ';' splitting in ScriptUtils. The IF prefix makes
-- the following CREATE conditional on object-existence metadata.

IF OBJECT_ID(N'DB_LOCK', N'U') IS NULL
CREATE TABLE DB_LOCK (
    id                   INT IDENTITY(1,1)    NOT NULL,
    lock_type            CHAR(1)              NOT NULL
        CONSTRAINT CKC_LOCK_TYPE_DB_LOCK CHECK (lock_type IN ('B','C','X','Y','Z')),
    lock_version         VARCHAR(50)          NOT NULL,
    hostname             VARCHAR(255)         NOT NULL,
    created_on           DATETIMEOFFSET       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT PK_DB_LOCK PRIMARY KEY (id),
    CONSTRAINT NDX_LOCK_TYPE UNIQUE (lock_type)
);

IF OBJECT_ID(N'DB_LOCK_HISTORY', N'U') IS NULL
CREATE TABLE DB_LOCK_HISTORY (
    id                   INT IDENTITY(1,1)    NOT NULL,
    lock_id              INT                  NOT NULL,
    lock_type            CHAR(1)              NOT NULL
        CONSTRAINT CKC_LOCK_TYPE_DB_LOCK_H CHECK (lock_type IN ('B','C','X','Y','Z')),
    lock_version         VARCHAR(50)          NOT NULL,
    hostname             VARCHAR(255)         NOT NULL,
    lock_acquired_on     DATETIMEOFFSET       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    lock_released_on     DATETIMEOFFSET       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT PK_DB_LOCK_HISTORY PRIMARY KEY (id)
);

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'NDX_DATE' AND object_id = OBJECT_ID(N'DB_LOCK_HISTORY'))
CREATE INDEX NDX_DATE ON DB_LOCK_HISTORY (lock_acquired_on);

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'NDX_LOCK' AND object_id = OBJECT_ID(N'DB_LOCK_HISTORY'))
CREATE INDEX NDX_LOCK ON DB_LOCK_HISTORY (lock_type, lock_version);

IF OBJECT_ID(N'DB_LOCK_LATEST', N'U') IS NULL
CREATE TABLE DB_LOCK_LATEST (
    lock_type            CHAR(1)              NOT NULL
        CONSTRAINT CKC_LOCK_TYPE_DB_LOCK_L CHECK (lock_type IN ('B','C','X','Y','Z')),
    lock_version         VARCHAR(50)          NOT NULL,
    hostname             VARCHAR(255)         NOT NULL,
    lock_acquired_on     DATETIMEOFFSET       NOT NULL,
    CONSTRAINT PK_DB_LOCK_LATEST PRIMARY KEY (lock_type)
);
