package de.flapdoodle.embed.process.store;

import de.flapdoodle.embed.process.builder.TypedProperty;
import de.flapdoodle.embed.process.config.store.IDownloadConfig;
import de.flapdoodle.embed.process.extract.ITempNaming;
import de.flapdoodle.embed.process.extract.UUIDTempNaming;
import de.flapdoodle.embed.process.io.directories.IDirectory;
import ru.yandex.qatools.embed.postgresql.Command;
import ru.yandex.qatools.embed.postgresql.config.PostgresDownloadConfigBuilder;
import ru.yandex.qatools.embed.postgresql.ext.SubdirTempDir;

public class PostgresArtifactStoreBuilder extends
        de.flapdoodle.embed.process.store.ArtifactStoreBuilder {

    public PostgresArtifactStoreBuilder defaults(Command command) {
        tempDir().setDefault(new SubdirTempDir());
        executableNaming().setDefault(new UUIDTempNaming());
        download().setDefault(new PostgresDownloadConfigBuilder().defaultsForCommand(command).build());
        downloader().setDefault(new Downloader());
        return this;
    }

    @Override
    public IArtifactStore build() {
        return new CachedPostgresArtifactStore(get(TypedProperty.with("DownloadConfig",IDownloadConfig.class)), get(TypedProperty.with("TempDir",IDirectory.class)), get(TypedProperty.with("ExecutableNaming",ITempNaming.class)), get(TypedProperty.with("Downloader",IDownloader.class)));
    }

}
