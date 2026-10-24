package dev.atlas.documentos;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** paginasMaximas limita o trabalho da extração síncrona; um PDF de 20 MiB pode ter milhares de páginas. */
@ConfigurationProperties(prefix = "atlas.documentos")
public record UploadProperties(Path diretorio, long tamanhoMaximoBytes, @DefaultValue("500") int paginasMaximas) {
}
