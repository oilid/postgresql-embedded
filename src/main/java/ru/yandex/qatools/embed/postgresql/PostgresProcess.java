package ru.yandex.qatools.embed.postgresql;

import static de.flapdoodle.embed.process.io.file.Files.forceDelete;
import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.sleep;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.apache.commons.io.FileUtils.readLines;
import static org.slf4j.LoggerFactory.getLogger;
import static ru.yandex.qatools.embed.postgresql.Command.CreateDb;
import static ru.yandex.qatools.embed.postgresql.Command.InitDb;
import static ru.yandex.qatools.embed.postgresql.Command.PgDump;
import static ru.yandex.qatools.embed.postgresql.Command.PgRestore;
import static ru.yandex.qatools.embed.postgresql.Command.Psql;
import static ru.yandex.qatools.embed.postgresql.PostgresStarter.getCommand;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;

import de.flapdoodle.embed.process.config.IRuntimeConfig;
import de.flapdoodle.embed.process.config.io.ProcessOutput;
import de.flapdoodle.embed.process.config.store.IDownloadConfig;
import de.flapdoodle.embed.process.distribution.Distribution;
import de.flapdoodle.embed.process.extract.IExtractedFileSet;
import de.flapdoodle.embed.process.io.Slf4jLevel;
import de.flapdoodle.embed.process.io.Slf4jStreamProcessor;
import de.flapdoodle.embed.process.io.directories.IDirectory;
import de.flapdoodle.embed.process.io.progress.Slf4jProgressListener;
import de.flapdoodle.embed.process.runtime.Executable;
import de.flapdoodle.embed.process.runtime.ProcessControl;
import de.flapdoodle.embed.process.store.IArtifactStore;
import de.flapdoodle.embed.process.store.IMutableArtifactStore;
import de.flapdoodle.embed.process.store.PostgresArtifactStore;
import de.flapdoodle.embed.process.store.PostgresArtifactStoreBuilder;
import ru.yandex.qatools.embed.postgresql.config.AbstractPostgresConfig.Storage;
import ru.yandex.qatools.embed.postgresql.config.IMutableDownloadConfig;
import ru.yandex.qatools.embed.postgresql.config.PostgresConfig;
import ru.yandex.qatools.embed.postgresql.config.PostgresDownloadConfigBuilder;
import ru.yandex.qatools.embed.postgresql.config.RuntimeConfigBuilder;
import ru.yandex.qatools.embed.postgresql.ext.LogWatchStreamProcessor;
import ru.yandex.qatools.embed.postgresql.ext.SubdirTempDir;

/**
 * postgres process
 */
public class PostgresProcess extends AbstractPGProcess<PostgresExecutable, PostgresProcess> {
	private static final int MAX_CREATEDB_TRIALS = 3;
	private static final int DEFAULT_CMD_TIMEOUT = 2000;
	private static Logger LOGGER = getLogger(PostgresProcess.class);
	private final IRuntimeConfig runtimeConfig;

	private volatile boolean processReady = false;
	private volatile boolean stopped = false;

	public PostgresProcess(Distribution distribution, PostgresConfig config, IRuntimeConfig runtimeConfig,
			PostgresExecutable executable) throws IOException {
		super(distribution, config, runtimeConfig, executable);
		this.runtimeConfig = runtimeConfig;
	}

	private static Integer runCmd(PostgresConfig config, IRuntimeConfig parentRuntimeCfg, Command cmd,
			String successOutput, Set<String> failOutput, String... args) {
		return runCmd(false, config, parentRuntimeCfg, cmd, successOutput, failOutput, args);
	}

	private static Integer runCmd(boolean silent, PostgresConfig config, IRuntimeConfig parentRuntimeCfg, Command cmd,
			String successOutput, Set<String> failOutput, String... args) {
		try {
			final LogWatchStreamProcessor logWatch = new LogWatchStreamProcessor(successOutput, failOutput,
					new Slf4jStreamProcessor(LOGGER, Slf4jLevel.TRACE));

			IArtifactStore artifactStore = parentRuntimeCfg.getArtifactStore();
			IDownloadConfig downloadCfg = ((PostgresArtifactStore) artifactStore).getDownloadConfig();

			if (downloadCfg instanceof IMutableDownloadConfig) {
				IDirectory tempDir = SubdirTempDir.defaultInstance();
				if (downloadCfg.getPackageResolver() instanceof PackagePaths) {
					tempDir = ((PackagePaths) downloadCfg.getPackageResolver()).getTempDir();
				}
				((IMutableDownloadConfig) downloadCfg).setPackageResolver(new PackagePaths(cmd, tempDir));
			} else {
				LOGGER.warn("Could not use the configured download configuration for '" + cmd.commandName()
						+ "', falling back to default!");
				downloadCfg = new PostgresDownloadConfigBuilder().defaultsForCommand(cmd)
						.progressListener(new Slf4jProgressListener(LOGGER)).build();
			}
			if (artifactStore instanceof IMutableArtifactStore) {
				((IMutableArtifactStore) artifactStore).setDownloadConfig(downloadCfg);
			} else {
				LOGGER.warn("Could not use the configured artifact store for '" + cmd.commandName()
						+ "', falling back to default!");
				artifactStore = new PostgresArtifactStoreBuilder().defaults(cmd).download(downloadCfg).build();
			}

			final IRuntimeConfig runtimeCfg = new RuntimeConfigBuilder().defaults(cmd).daemonProcess(false)
					.processOutput(new ProcessOutput(logWatch, logWatch, logWatch)).artifactStore(artifactStore)
					.commandLinePostProcessor(parentRuntimeCfg.getCommandLinePostProcessor()).build();

			final PostgresConfig postgresConfig = new PostgresConfig(config).withArgs(args);
			if (Command.InitDb == cmd) {
				postgresConfig.withAdditionalInitDbParams(config.getAdditionalInitDbParams());
			}
			final Executable<?, ? extends AbstractPGProcess> exec = getCommand(cmd, runtimeCfg).prepare(postgresConfig);
			AbstractPGProcess proc = exec.start();
			logWatch.waitForResult(DEFAULT_CMD_TIMEOUT);
			// the exit value of the process. By convention, 0 indicates normal termination.
			int exitCode = proc.waitFor();
			return Integer.valueOf(exitCode);
		} catch (IOException e) {
			if (!silent) {
				LOGGER.warn("Failed to run command {}", cmd.commandName(), e);
			}

		} catch (InterruptedException e) {
			if (!silent) {
				LOGGER.warn("Failed to run command {}", cmd.commandName(), e);
			}
		}
		return null;
	}

	private static boolean shutdownPostgres(PostgresConfig config, IRuntimeConfig runtimeConfig) {
		Set<String> emptySet = emptySet();
		try {
			Integer exitCode = runCmd(true, config, runtimeConfig, Command.PgCtl, "server stopped", emptySet, "stop");
			return Integer.valueOf(0).equals(exitCode);
		} catch (Exception e) {
			LOGGER.trace("Failed to stop postgres by pg_ctl!", e);
		}
		return false;
	}

	@Override
	protected synchronized void stopInternal() {
		if (!stopped && isProcessRunning()) {
			stopped = true;
			LOGGER.info("trying to stop postgresql");
			if (!sendStopToPostgresqlInstance() && !sendTermToProcess() && waitUntilProcessHasStopped(2000)) {
				LOGGER.warn("could not stop postgresql with pg_ctl/SIGTERM, trying to kill it...");
				if (!sendKillToProcess() && !tryKillToProcess() && waitUntilProcessHasStopped(3000)) {
					LOGGER.warn("could not kill postgresql within 4s!");
				}
			}
		}
		if (waitUntilProcessHasStopped(5000)) {
			LOGGER.error("Postgres has not been stopped within 10s! Something's wrong!");
		}
		deleteTempFiles();
	}

	private boolean waitUntilProcessHasStopped(int timeoutMillis) {
		long started = currentTimeMillis();
		while (currentTimeMillis() - started < timeoutMillis && isProcessRunning()) {
			try {
				sleep(50);
			} catch (InterruptedException e) {
				LOGGER.warn("Failed to wait with timeout until the process has been killed", e);
			}
		}
		return isProcessRunning();
	}

	protected final boolean sendStopToPostgresqlInstance() {
		final boolean result = shutdownPostgres(getConfig(), runtimeConfig);
		if (runtimeConfig.getArtifactStore() instanceof PostgresArtifactStore) {
			final IDirectory tempDir = ((PostgresArtifactStore) runtimeConfig.getArtifactStore()).getTempDir();
			if (tempDir != null && tempDir.asFile() != null && tempDir.isGenerated()) {
				LOGGER.info("Cleaning up after the embedded process (removing {})...",
						tempDir.asFile().getAbsolutePath());
				forceDelete(tempDir.asFile());
			}
		}
		return result;
	}

	@Override
	protected void onBeforeProcess(IRuntimeConfig runtimeConfig) throws IOException {
		super.onBeforeProcess(runtimeConfig);
		PostgresConfig config = getConfig();

		final File dbDir = config.storage().dbDir();
		if (dbDir.exists() && dbDir.listFiles() != null && dbDir.listFiles().length > 0) {
			return;
		}

		Set<String> emptySet = emptySet();
		runCmd(config, runtimeConfig, InitDb, "Success. You can now start the database server using", emptySet);
	}

	@Override
	protected List<String> getCommandLine(Distribution distribution, PostgresConfig config, IExtractedFileSet exe)
			throws IOException {
		List<String> ret = new ArrayList<String>();
		String name = config.supportConfig().getName();

		if ("postgres".equals(name)) {
			ret.addAll(asList(exe.executable().getAbsolutePath(), "-p", String.valueOf(config.net().port()), "-h",
					config.net().host(), "-D", config.storage().dbDir().getAbsolutePath()));
		} else if ("pg_ctl".equals(name)) {
			ret.addAll(asList(exe.executable().getAbsolutePath(),
					String.format("-o \"-p %s\" \"-h %s\"", config.net().port(), config.net().host()), "-D",
					config.storage().dbDir().getAbsolutePath(), "-w", "start"));
		} else {
			throw new RuntimeException("Failed to launch Postgres: Unknown command " + name + "!");
		}
		return ret;
	}

	protected void deleteTempFiles() {
		final Storage storage = getConfig().storage();
		if (storage.dbDir() == null) {
			return;
		}
		if (!storage.isTmpDir()) {
			return;
		}

		if (!forceDelete(storage.dbDir())) {
			LOGGER.warn("Could not delete temp db dir: {}", storage.dbDir());
		}
	}

	@Override
	protected final void onAfterProcessStart(ProcessControl process, IRuntimeConfig runtimeConfig) throws IOException {
		final Storage storage = getConfig().storage();
		// final Path pidFilePath = Paths.get(storage.dbDir().getAbsolutePath(),
		// "postmaster.pid");
		final File pidFile = new File(storage.dbDir().getAbsolutePath(), "postmaster.pid");
		int timeout = TIMEOUT;
		while (!pidFile.exists() && ((timeout = timeout - 100) > 0)) {
			try {
				sleep(100);
			} catch (InterruptedException ie) {
				/* safe to ignore */ }
		}
		int pid = -1;
		try {
			pid = Integer.valueOf(readLines(pidFile).get(0));
		} catch (Exception e) {
			LOGGER.info("Failed to read PID file ({})", e.getMessage());
		}
		if (pid != -1) {
			setProcessId(pid);
		} else {
			// fallback, try to read pid file. will throw IOException if that
			// fails
			setProcessId(getPidFromFile(pidFile()));
		}

		int trial = 0;
		do {
			Integer exitCode = runCmd(getConfig(), runtimeConfig, CreateDb, "",
					new HashSet<String>(singleton("database creation failed")), storage.dbName());
			try {
				if (Integer.valueOf(0).equals(exitCode)) {
					this.processReady = true;
					break;
				}
				LOGGER.warn("Could not create database first time ({} of {} trials)", trial, MAX_CREATEDB_TRIALS);
				sleep(100);
			} catch (InterruptedException ie) {
				/* safe to ignore */ }
		} while (trial++ < MAX_CREATEDB_TRIALS);
	}

	/**
	 * Import into database from file
	 *
	 * @param file
	 *            The file to import into database
	 * @return the exit value of the process. By convention, 0 indicates normal termination. {@code null} or other values indicates an error.
	 */
	public Integer importFromFile(File file) {
		return importFromFileWithArgs(file);
	}

	/**
	 * Import into database from file with additional args
	 *
	 * @param file
	 * @param cliArgs
	 *            additional arguments for psql (be sure to separate args from
	 *            their values)
	 * @return the exit value of the process. By convention, 0 indicates normal termination. {@code null} or other values indicates an error.
	 */
	public Integer importFromFileWithArgs(File file, String... cliArgs) {
		Integer exitCode = null;
		if (file.exists()) {
			String[] args = { "-U", getConfig().credentials().username(), "-d", getConfig().storage().dbName(), "-h",
					getConfig().net().host(), "-p", String.valueOf(getConfig().net().port()), "-f",
					file.getAbsolutePath() };
			if (cliArgs != null && cliArgs.length != 0) {
				args = ArrayUtils.addAll(args, cliArgs);
			}
			exitCode = runCmd(getConfig(), runtimeConfig, Psql, "",
					new HashSet<String>(singletonList("import into " + getConfig().storage().dbName() + " failed")),
					args);
		}
		return exitCode;
	}

	/**
	 * Import into database from file with additional args
	 *
	 * @param file
	 * @param cliArgs
	 *            additional arguments for psql (be sure to separate args from
	 *            their values)
	 * @return the exit value of the process. By convention, 0 indicates normal termination. {@code null} or other values indicates an error.
	 */
	public Integer restoreFromFile(File file, String... cliArgs) {
		Integer exitCode = null;
		if (file.exists()) {
			String[] args = { "-U", getConfig().credentials().username(), "-d", getConfig().storage().dbName(), "-h",
					getConfig().net().host(), "-p", String.valueOf(getConfig().net().port()) };
			if (cliArgs != null && cliArgs.length != 0) {
				args = ArrayUtils.addAll(args, cliArgs);
			}
			args = ArrayUtils.add(args, file.getAbsolutePath()); 
			exitCode = runCmd(getConfig(), runtimeConfig, PgRestore, "",
					new HashSet<String>(singletonList("restore into " + getConfig().storage().dbName() + " failed")),
					args);
		}
		return exitCode;
	}
	
	public Integer exportToFile(File file, String... cliArgs) {
		String[] args = { "-U", getConfig().credentials().username(), "-d", getConfig().storage().dbName(), "-h",
				getConfig().net().host(), "-p", String.valueOf(getConfig().net().port()), "-f",
				file.getAbsolutePath() };
		if (ArrayUtils.isNotEmpty(cliArgs)) {
			args = ArrayUtils.addAll(args, cliArgs);
		}
		Integer exitCode = runCmd(getConfig(), runtimeConfig, PgDump, "",
				new HashSet<String>(singletonList("export from " + getConfig().storage().dbName() + " failed")), args);
		return exitCode;
	}

	public Integer exportSchemeToFile(File file, String... cliArgs) {
		String[] args = { "-U", getConfig().credentials().username(), "-d", getConfig().storage().dbName(), "-h",
				getConfig().net().host(), "-p", String.valueOf(getConfig().net().port()), "-f", file.getAbsolutePath(),
				"-s" };
		if (ArrayUtils.isNotEmpty(cliArgs)) {
			args = ArrayUtils.addAll(args, cliArgs);
		}
		Integer exitCode = runCmd(getConfig(), runtimeConfig, PgDump, "",
				new HashSet<String>(singletonList("export from " + getConfig().storage().dbName() + " failed")), args);
		return exitCode;
	}

	public Integer exportDataToFile(File file, String... cliArgs) {
		String[] args = { "-U", getConfig().credentials().username(), "-d", getConfig().storage().dbName(), "-h",
				getConfig().net().host(), "-p", String.valueOf(getConfig().net().port()), "-f", file.getAbsolutePath(),
				"-a" };
		if (ArrayUtils.isNotEmpty(cliArgs)) {
			args = ArrayUtils.addAll(args, cliArgs);
		}
		Integer exitCode = runCmd(getConfig(), runtimeConfig, PgDump, "",
				new HashSet<String>(singletonList("export from " + getConfig().storage().dbName() + " failed")), args);
		return exitCode;
	}

	public boolean isProcessReady() {
		return processReady;
	}

	@Override
	protected void cleanupInternal() {
	}
}
