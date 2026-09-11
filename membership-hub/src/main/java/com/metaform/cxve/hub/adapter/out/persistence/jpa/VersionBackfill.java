package com.metaform.cxve.hub.adapter.out.persistence.jpa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time migration for tables that predate the optimistic-lock column. Hibernate declares
 * {@code @Version} columns NOT NULL, and Postgres refuses to ALTER a NOT NULL column without a
 * default onto a populated table — so ddl-auto's own attempt fails (a startup warning) and this
 * runner owns the migration instead: add the column nullable, zero the legacy rows (a NULL
 * version could never satisfy the versioned-update WHERE clause — every write would fail as a
 * phantom conflict forever). Idempotent, a no-op once done; runs before readiness flips, so no
 * traffic sees the half-migrated table. The pragmatic stand-in until the schema is
 * migration-managed.
 */
@Component
@Profile("!test")
class VersionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VersionBackfill.class);

    private final JdbcTemplate jdbcTemplate;

    VersionBackfill(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("ALTER TABLE membership ADD COLUMN IF NOT EXISTS version bigint");
        var updated = jdbcTemplate.update("UPDATE membership SET version = 0 WHERE version IS NULL");
        if (updated > 0) {
            log.info("Backfilled the optimistic-lock version on {} pre-existing membership row(s)", updated);
        }
    }
}
