package com.convenientclipboard.files;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs against an explicitly supplied PostgreSQL; temporary tables leave its existing data untouched. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class FileRepositoryAdminTest {
    SingleConnectionDataSource dataSource;
    FileRepository files;
    List<Fixture> fixtures;

    record Fixture(StoredFile file, String ownerName) {}

    @BeforeEach void setUp() throws Exception {
        var connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("TEST_POSTGRES_USERNAME", "clipboard_test"),
                System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "clipboard_test"));
        dataSource = new SingleConnectionDataSource(connection, true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create temporary table app_users (id uuid primary key, username varchar(255))");
        jdbc.execute("""
                create temporary table stored_files (
                    id uuid primary key, owner_id uuid, original_name varchar(255), content_type varchar(255),
                    size_bytes bigint, object_key varchar(255), share_token varchar(255), permanent boolean,
                    expires_at timestamptz, location varchar(16), provider_id uuid, created_at timestamptz)
                """);
        files = new FileRepository(jdbc);
        fixtures = new ArrayList<>();
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        jdbc.update("insert into app_users(id,username) values (?,?)", owner, "Alice");
        int index = 0;
        for (String location : List.of("LOCAL", "S3", "NFS", "SMB")) {
            for (boolean permanent : List.of(false, true)) {
                for (boolean anonymous : List.of(false, true)) {
                    index++;
                    UUID id = new UUID(0, index);
                    // Include already expired temporary files and equal timestamps to exercise deterministic pages.
                    Instant created = Instant.parse("2026-01-01T00:00:00Z");
                    var file = new StoredFile(id, anonymous ? null : owner,
                            index == 1 ? "literal %_ ' OR true --.txt" : "Quarter-" + index + ".txt", "text/plain", index,
                            id.toString(), "token-" + index, permanent, permanent ? null : created.plusSeconds(60),
                            location, location.equals("LOCAL") ? null : UUID.randomUUID(), created);
                    files.insert(file);
                    fixtures.add(new Fixture(file, anonymous ? null : "Alice"));
                }
            }
        }
    }

    @AfterEach void close() {
        if (dataSource != null) dataSource.destroy();
    }

    static Stream<Arguments> filters() {
        return Stream.of("", "LOCAL", "S3", "NFS", "SMB")
                .flatMap(location -> Stream.of("", "PERMANENT", "TEMPORARY").map(retention -> Arguments.of(location, retention)));
    }

    @ParameterizedTest
    @MethodSource("filters")
    void filtersSearchAndPaginationShareTheSameResults(String location, String retention) {
        for (String query : List.of("", "QUARTER", "ALICE", "匿名用户", "00000000000a", "%_", "' OR true --")) {
            List<Fixture> expected = fixtures.stream().filter(f -> matches(f, query, location, retention))
                    .sorted(Comparator.comparing((Fixture f) -> f.file().id().toString()).reversed()).toList();
            assertThat(files.adminCount(query, location, retention)).isEqualTo(expected.size());
            List<UUID> actual = new ArrayList<>();
            for (int page = 0; page <= expected.size() / 3; page++) {
                var rows = files.adminPage(page, 3, query, location, retention);
                assertThat(rows).hasSize(Math.min(3, Math.max(0, expected.size() - page * 3)));
                actual.addAll(rows.stream().map(row -> row.file().id()).toList());
            }
            assertThat(actual).containsExactlyElementsOf(expected.stream().map(f -> f.file().id()).toList());
        }
    }

    @Test void originalRepositoryOverloadsStillReturnAllMatchingFiles() {
        assertThat(files.adminCount("ALICE")).isEqualTo(8);
        assertThat(files.adminPage(0, 100, "ALICE")).hasSize(8);
    }

    @Test void veryLargePageDoesNotOverflowOffsetOrChangeCount() {
        assertThat(files.adminPage(Integer.MAX_VALUE, 100, "", "", "")).isEmpty();
        assertThat(files.adminCount("", "", "")).isEqualTo(16);
    }

    private boolean matches(Fixture fixture, String query, String location, String retention) {
        StoredFile file = fixture.file();
        String search = query.toLowerCase(Locale.ROOT);
        String owner = fixture.ownerName() == null ? "匿名用户" : fixture.ownerName();
        return (file.originalName().toLowerCase(Locale.ROOT).contains(search)
                || owner.toLowerCase(Locale.ROOT).contains(search) || file.id().toString().contains(search))
                && (location.isEmpty() || location.equals(file.location()))
                && (retention.isEmpty() || file.permanent() == retention.equals("PERMANENT"));
    }
}
