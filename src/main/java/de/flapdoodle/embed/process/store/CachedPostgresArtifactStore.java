package de.flapdoodle.embed.process.store;

import static de.flapdoodle.embed.process.config.store.FileType.Executable;
import static de.flapdoodle.embed.process.config.store.FileType.Library;
import static de.flapdoodle.embed.process.extract.ImmutableExtractedFileSet.builder;
import static org.apache.commons.io.FileUtils.iterateFiles;
import static org.apache.commons.io.filefilter.TrueFileFilter.TRUE;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.flapdoodle.embed.process.config.store.FileSet;
import de.flapdoodle.embed.process.config.store.FileSet.Entry;
import de.flapdoodle.embed.process.config.store.FileType;
import de.flapdoodle.embed.process.config.store.IDownloadConfig;
import de.flapdoodle.embed.process.distribution.Distribution;
import de.flapdoodle.embed.process.extract.IExtractedFileSet;
import de.flapdoodle.embed.process.extract.ITempNaming;
import de.flapdoodle.embed.process.extract.ImmutableExtractedFileSet.Builder;
import de.flapdoodle.embed.process.io.directories.IDirectory;

public class CachedPostgresArtifactStore extends PostgresArtifactStore {
	private static final Logger LOGGER = LoggerFactory.getLogger(CachedPostgresArtifactStore.class);
	private IDownloadConfig downloadConfig;
	private IDirectory eDir;

	public CachedPostgresArtifactStore(IDownloadConfig downloadConfig, IDirectory eDir, ITempNaming executableNaming,
			IDownloader downloader) {
		super(downloadConfig, eDir, executableNaming, downloader);
		this.downloadConfig = downloadConfig;
		this.eDir = eDir;
	}

	@Override
	public void removeFileSet(Distribution distribution, IExtractedFileSet all) {
		// do nothing
	}

	@Override
	public IExtractedFileSet extractFileSet(Distribution distribution) throws IOException {
		try {
			final File dir = this.eDir.asFile();
			final FileSet filesSet = downloadConfig.getPackageResolver().getFileSet(distribution);
			// final Path path = get(dir.getPath(),
			// "pgsql" + "-" + distribution.getVersion().asInDownloadPath(),
			// "pgsql");
			File pathFile = new File(dir.getPath(), "pgsql" + "-" + distribution.getVersion().asInDownloadPath());
			File pgsqlPathFile = new File(pathFile, "pgsql");
			if (pgsqlPathFile.exists()) {
				final Builder extracted = builder(dir).baseDirIsGenerated(false);
				Iterator<File> iterateFiles = iterateFiles(pgsqlPathFile, TRUE, TRUE);
				while (iterateFiles.hasNext()) {
					File file = iterateFiles.next();
					FileType type = Library;
					List<Entry> entries = filesSet.entries();
					for (Entry entry : entries) {
						boolean matches = entry.matchingPattern().matcher(file.getPath()).matches();
						if (matches) {
							type = Executable;
							break;
						}
					}
					extracted.file(type, file);
				}

				// .forEachRemaining(file -> {
				// FileType type = Library;
				// if (filesSet.entries().stream()
				// .anyMatch(entry ->
				// entry.matchingPattern().matcher(file.getPath()).matches())) {
				// type = Executable;
				// }
				// extracted.file(type, file);
				// });
				return extracted.build();
			} else {
				return super.extractFileSet(distribution);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to extract file set", e);
			return new EmptyFileSet();
		}
	}
}
