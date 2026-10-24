package dev.atlas.documentos;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.documentos")
public record UploadProperties(Path diretorio, long tamanhoMaximoBytes) {
}
