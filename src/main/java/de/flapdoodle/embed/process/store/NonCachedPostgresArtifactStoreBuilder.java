package de.flapdoodle.embed.process.store;

import de.flapdoodle.embed.process.builder.TypedProperty;
import de.flapdoodle.embed.process.config.store.IDownloadConfig;
import de.flapdoodle.embed.process.extract.ITempNaming;
import de.flapdoodle.embed.process.io.directories.IDirectory;

public class NonCachedPostgresArtifactStoreBuilder extends PostgresArtifactStoreBuilder {

    @Override
    public IArtifactStore build() {
        return new PostgresArtifactStore(get(TypedProperty.with("DownloadConfig",IDownloadConfig.class)), get(TypedProperty.with("TempDir",IDirectory.class)), get(TypedProperty.with("ExecutableNaming",ITempNaming.class)), get(TypedProperty.with("Downloader",IDownloader.class)));
    }
}
