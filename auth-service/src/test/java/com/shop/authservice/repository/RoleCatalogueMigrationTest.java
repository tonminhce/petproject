package com.shop.authservice.repository;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-056: the changeset that aligns the role table with the Keycloak realm.
 *
 * <p>The test applies the real Liquibase changesets against a real
 * PostgreSQL, in two steps:</p>
 * <ol>
 *   <li>the pre-F-056 changesets - the database still holds the fictional PM
 *       role and a user linked to it (the legacy state);</li>
 *   <li>the real master changelog - only changeset 006 is new, and it must
 *       add MANAGER and SERVICE and remove PM together with its links.</li>
 * </ol>
 *
 * <p>Running it against a legacy database is the point: the changeset deletes
 * the {@code user_role} links BEFORE the role row, because of the foreign
 * key. The other order fails the migration on every existing database.</p>
 */
@Testcontainers
class RoleCatalogueMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_roles")
            .withUsername("auth")
            .withPassword("auth");

    @Test
    @DisplayName("the migration adds MANAGER and SERVICE and removes PM with its links")
    void theMigrationAlignsTheRoleTableWithTheRealm() throws Exception {
        try (Connection connection = POSTGRES.createConnection("")) {
            // 1. the legacy schema and the legacy PM assignment
            update(connection, "db/changelog/legacy-roles-changelog.yaml");
            execute(connection, "insert into users (user_id, full_name, user_name, email, gender) values "
                    + "(gen_random_uuid(), 'Legacy PM', 'legacy-pm', 'legacy-pm@example.com', 'OTHER')");
            execute(connection, "insert into user_role (user_id, role_id) "
                    + "select u.user_id, r.id from users u, roles r "
                    + "where u.user_name = 'legacy-pm' and r.role_name = 'PM'");

            assertThat(query(connection, "select role_name from roles order by role_name"))
                    .containsExactly("ADMIN", "PM", "USER");

            // 2. the real changelog - only 006 is new
            update(connection, "db/changelog/db.changelog-master.yaml");

            assertThat(query(connection, "select role_name from roles order by role_name"))
                    .as("the role table must hold exactly the realm roles")
                    .containsExactly("ADMIN", "MANAGER", "SERVICE", "USER");
            assertThat(query(connection, "select count(*) from roles where role_name = 'PM'"))
                    .as("the fictional PM role must be gone").containsExactly("0");
            assertThat(query(connection, "select count(*) from user_role"))
                    .as("the links to the removed role must be gone as well").containsExactly("0");
            assertThat(query(connection, "select count(*) from users where user_name = 'legacy-pm'"))
                    .as("the migration must not touch the users themselves").containsExactly("1");
        }
    }

    private void update(Connection connection, String changelog) throws Exception {
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        Liquibase liquibase = new Liquibase(changelog, new ClassLoaderResourceAccessor(), database);
        liquibase.update(new Contexts(), new LabelExpression());
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<String> query(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        }
    }
}
