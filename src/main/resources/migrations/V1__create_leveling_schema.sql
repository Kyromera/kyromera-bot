
CREATE TABLE leveling_users
(
    guildid    BIGINT  NOT NULL,
    userid     BIGINT  NOT NULL,
    xp         INTEGER NOT NULL DEFAULT 0,
    level      INTEGER NOT NULL DEFAULT 0,
    pingactive BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (guildid, userid)
);

CREATE TABLE leveling_timestamps
(
    guildid     BIGINT      NOT NULL,
    userid      BIGINT      NOT NULL,
    type        VARCHAR(16) NOT NULL CHECK (type IN ('text', 'vc')),
    last_reward BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (guildid, userid, type)
);

CREATE TABLE leveling_roles
(
    guildid    BIGINT      NOT NULL,
    roleid     BIGINT      NOT NULL,
    role_type  VARCHAR(16) NOT NULL CHECK (role_type IN ('reward', 'multiplier')),
    level      INTEGER,
    multiplier DOUBLE PRECISION CHECK (multiplier >= 0),
    PRIMARY KEY (guildid, roleid, role_type)
);

CREATE INDEX idx_roles_guild_type ON leveling_roles (guildid, role_type);
CREATE INDEX idx_roles_multiplier ON leveling_roles (multiplier) WHERE role_type = 'multiplier';

CREATE TABLE leveling_whitelisted_channels
(
    guildid   BIGINT NOT NULL,
    channelid BIGINT NOT NULL,
    PRIMARY KEY (guildid, channelid)
);

CREATE TABLE leveling_whitelisted_roles
(
    guildid BIGINT NOT NULL,
    roleid  BIGINT NOT NULL,
    PRIMARY KEY (guildid, roleid)
);

CREATE TABLE leveling_settings
(
    guildid                BIGINT PRIMARY KEY,
    text_enabled           BOOLEAN          NOT NULL DEFAULT TRUE,
    vc_enabled             BOOLEAN          NOT NULL DEFAULT TRUE,
    text_multi             DOUBLE PRECISION NOT NULL DEFAULT 1.0 CHECK (text_multi >= 0),
    vc_multi               DOUBLE PRECISION NOT NULL DEFAULT 1.0 CHECK (vc_multi >= 0),
    levelup_channel        BIGINT,
    levelup_message        TEXT,
    levelup_message_reward TEXT,
    retain_roles           BOOLEAN          NOT NULL DEFAULT FALSE,
    last_recalc            BIGINT                    DEFAULT 0,
    whitelist_mode         BOOLEAN          NOT NULL DEFAULT FALSE,
    levelup_announce_mode  VARCHAR(16)      NOT NULL DEFAULT 'current'
        CHECK (levelup_announce_mode IN ('disabled', 'current', 'dm', 'custom'))
);
