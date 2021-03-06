package ru.yandex.qatools.embed.postgresql;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static ru.yandex.qatools.embed.postgresql.EmbeddedPostgres.DEFAULT_DB_NAME;
import static ru.yandex.qatools.embed.postgresql.EmbeddedPostgres.DEFAULT_PASSWORD;
import static ru.yandex.qatools.embed.postgresql.EmbeddedPostgres.DEFAULT_USER;
import static ru.yandex.qatools.embed.postgresql.EmbeddedPostgres.cachedRuntimeConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.common.base.Optional;

public class EmbeddedPostgresTest {

	private EmbeddedPostgres postgres;
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Before
	public void setUp() throws Exception {
		postgres = new EmbeddedPostgres();
	}

	@After
	public void tearDown() throws Exception {
		Optional<PostgresProcess> process = postgres.getProcess();
		boolean present = process.isPresent();
		if (present) {
			process.get().stop();
		}
	}

	@Test
	public void itShouldStartWithDefaults() throws Exception {
		final String url = postgres.start();
		assertThat(url, startsWith("jdbc:postgresql://localhost:"));
		assertThat(url, endsWith(format("/%s?user=%s&password=%s", DEFAULT_DB_NAME, DEFAULT_USER, DEFAULT_PASSWORD)));
		ensurePostgresIsWorking(url);
	}

	@Test
	public void itShouldBeEmptyForNonStartedInstance() throws Exception {
		assertThat(postgres.getConnectionUrl().isPresent(), equalTo(false));
		assertThat(postgres.getConfig().isPresent(), equalTo(false));
		assertThat(postgres.getProcess().isPresent(), equalTo(false));
	}

	@Test(expected = IllegalStateException.class)
	public void itShouldThrowExceptionForNonStartedInstance() throws Exception {
		postgres.stop();
	}

	@Test
	public void itShouldWorkForNonDefaultConfig() throws Exception {
		List<String> emptyList = emptyList();
		final String url = postgres.start("localhost", 15433, "pgDataBase", "pgUser", "pgPassword", emptyList);
		assertThat(url, equalTo("jdbc:postgresql://localhost:15433/pgDataBase?user=pgUser&password=pgPassword"));
		ensurePostgresIsWorking(url);
	}

	@Test
	public void itShouldWorkWithCachedRuntimeConfig() throws Exception {
		File tempFolder = temporaryFolder.newFolder("pgembed");
		final String url = postgres
				.start(cachedRuntimeConfig(tempFolder));
		ensurePostgresIsWorking(url);
	}

	private void ensurePostgresIsWorking(String url) throws ClassNotFoundException {
//		Class.forName("org.postgresql.Driver");
		try {
			final Connection conn = DriverManager.getConnection(url);
			assertThat(conn.createStatement().execute("CREATE TABLE films (code char(5));"), is(false));
			assertThat(conn.createStatement().execute("INSERT INTO films VALUES ('movie');"), is(false));
			final Statement statement = conn.createStatement();
			assertThat(statement.execute("SELECT * FROM films;"), is(true));
			assertThat(statement.getResultSet().next(), is(true));
			assertThat(statement.getResultSet().getString("code"), is("movie"));
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}
}
