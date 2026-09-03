package com.demo.auth_service.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time repair for a real incident found the day the user-management phase shipped:
 * {@code Credential.enabled} was added as a primitive {@code boolean NOT NULL} with no
 * default, and this service runs {@code ddl-auto: update}. MySQL here is not in strict
 * mode, so it silently backfilled the new column to {@code 0}/false on every
 * pre-existing row instead of erroring -- which meant every credential created before
 * this deploy, including {@code admin}, became indistinguishable from a disabled
 * account and could no longer log in ({@code AuthService.login} deliberately throws the
 * same error for both, so it looked like "wrong password").
 *
 * <p>Nobody could have used the new disable feature successfully before this fix runs
 * -- logging in as ADMIN to reach it was itself broken -- so any row with
 * {@code enabled = false} at the moment this first runs is unambiguously a backfill
 * artifact, never a deliberate admin action. That assumption is what makes a blanket
 * repair safe, but only <b>once</b>: a genuine admin disable issued after this runs must
 * never be silently reverted by a later pod restart, so a marker table guards it to run
 * at most one time, ever.
 *
 * <p>The lesson (already documented once for {@code Order.direct} -- see
 * {@code learn/05-jpa-hibernate-and-databases.md}) is worth repeating: a {@code NOT NULL}
 * column added via {@code ddl-auto: update} has no way to backfill existing rows
 * correctly. {@code Credential.enabled} didn't follow that precaution and this is the
 * result.
 */
@Component
@Slf4j
public class EnabledBackfillRepair implements ApplicationRunner {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {

        entityManager.createNativeQuery(
                "CREATE TABLE IF NOT EXISTS enabled_backfill_repair_marker (id INT PRIMARY KEY)")
                .executeUpdate();

        Number alreadyRan = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM enabled_backfill_repair_marker").getSingleResult();
        if (alreadyRan.intValue() > 0) {
            return;
        }

        int repaired = entityManager.createNativeQuery(
                "UPDATE credential SET enabled = TRUE WHERE enabled = FALSE")
                .executeUpdate();

        entityManager.createNativeQuery(
                "INSERT INTO enabled_backfill_repair_marker (id) VALUES (1)")
                .executeUpdate();

        log.warn("EnabledBackfillRepair: re-enabled {} credential row(s) disabled by the "
                + "ddl-auto backfill bug (see plan.md / Agent.md for the incident writeup); "
                + "this repair will not run again.", repaired);
    }
}
