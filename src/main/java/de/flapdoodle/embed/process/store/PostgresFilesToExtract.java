package de.flapdoodle.embed.process.store;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.flapdoodle.embed.process.config.store.FileSet;
import de.flapdoodle.embed.process.config.store.FileType;
import de.flapdoodle.embed.process.distribution.Distribution;
import de.flapdoodle.embed.process.extract.CommonsArchiveEntryAdapter;
import de.flapdoodle.embed.process.extract.FilesToExtract;
import de.flapdoodle.embed.process.extract.IArchiveEntry;
import de.flapdoodle.embed.process.extract.IExtractionMatch;
import de.flapdoodle.embed.process.extract.ITempNaming;
import de.flapdoodle.embed.process.io.directories.IDirectory;
import de.flapdoodle.embed.process.io.file.Files;

/**
 * @author Ilya Sadykov Hacky strategy of extraction. Allows to extract the full
 *         postgres binaries.
 */
public class PostgresFilesToExtract extends FilesToExtract {
	private static final Logger LOGGER = LoggerFactory.getLogger(PostgresFilesToExtract.class);

	private static final String SKIP_PATTERN = "pgsql/(doc|include|symbols|pgAdmin[^/]*)/.+";
	private static final String EXECUTABLE_PATTERN = "pgsql/bin/.+";

	private final FileSet fileSet;
	private final String extractBasePath;

	public PostgresFilesToExtract(IDirectory dirFactory, ITempNaming executableNaming, FileSet fileSet,
			Distribution distribution) {
		super(dirFactory, executableNaming, fileSet);
		this.fileSet = fileSet;

		if (dirFactory.asFile() != null) {
			final File file = new File(dirFactory.asFile(), "pgsql-" + distribution.getVersion().asInDownloadPath());
			if (!file.exists()) {
				// noinspection ResultOfMethodCallIgnored
				file.mkdir();
			}
			this.extractBasePath = file.getPath();
		} else {
			this.extractBasePath = null;
		}
	}

	/**
	 * This is actually the very dirty hack method to adopt the Flapdoodle's API
	 * to the compatible way to extract and run TODO: hacky method. Should be
	 * considered for complete rewriting //NOSONAR
	 */
	@Override
	public IExtractionMatch find(final IArchiveEntry entry) {// NOSONAR
		if (this.extractBasePath == null) {
			return null;
		}
		if (entry.getName().matches(SKIP_PATTERN)) {
			return null;
		}

		// final Path path = Paths.get(this.extractBasePath, entry.getName());
		final File outputFile = new File(this.extractBasePath, entry.getName());
		return new IExtractionMatch() { // NOSONAR
			@Override
			public File write(InputStream source, long size) throws IOException { // NOSONAR
				boolean isSymLink = false;
				String linkName = "";
				if (entry instanceof CommonsArchiveEntryAdapter) {
					try {
						// hack to allow symlinks extraction (ONLY tar archives
						// are supported!)
						Field archiveEntryField = CommonsArchiveEntryAdapter.class.getDeclaredField("_entry");
						archiveEntryField.setAccessible(true);
						ArchiveEntry archiveEntry = (ArchiveEntry) archiveEntryField.get(entry);
						if (archiveEntry instanceof TarArchiveEntry
								&& (isSymLink = ((TarArchiveEntry) archiveEntry).isSymbolicLink())) { // NOSONAR
							linkName = ((TarArchiveEntry) archiveEntry).getLinkName();
						}
						archiveEntry.getSize();
					} catch (NoSuchFieldException e) {
						throw new RuntimeException("Check the version of de.flapdoodle.embed.process API. " + // NOSONAR
								"Has it changed?", e);
					} catch (IllegalAccessException e) {
						throw new RuntimeException("Check the version of de.flapdoodle.embed.process API. " + // NOSONAR
								"Has it changed?", e);
					}
				}
				// I got some problems with concurrency. Not sure this is
				// required.
				synchronized (PostgresFilesToExtract.class) {
					// final File outputFile = path.toFile();
					if (entry.isDirectory()) {
						if (!outputFile.exists()) {
							Files.createDir(outputFile);
						}
					} else {
						if (!outputFile.exists()) { // prevent double extraction
													// (for other binaries)
							if (isSymLink) {
								try { // NOSONAR
									// File parentFile =
									// outputFile.getParentFile();
									// File linkFileTarget = new
									// File(parentFile, linkName);

									// final Path target =
									// path.getParent().resolve(Paths.get(linkName));
									// TODO: Wie kann man unter java6
									// symbolische Links erstellen???
									// java.nio.file.Files.createSymbolicLink(outputFile.toPath(),
									// target);
								} catch (Exception e) {
									LOGGER.trace("Failed to extract symlink", e);
								}
							} else {
								Files.write(source, outputFile);
							}
						}
						// hack to mark binaries as executable
						if (entry.getName().matches(EXECUTABLE_PATTERN)) {
							outputFile.setExecutable(true);
						}
					}
					return outputFile;
				}
			}

			@Override
			public FileType type() {
				// does this archive entry match to any of the provided fileset
				// entries?
				for (FileSet.Entry matchingEntry : fileSet.entries()) {
					if (matchingEntry.matchingPattern().matcher(outputFile.getPath()).matches()) {
						return matchingEntry.type();
					}
				}
				// Otherwise - it's just an library file
				return FileType.Library;
			}
		};
	}
}
