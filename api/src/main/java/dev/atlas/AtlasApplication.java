package dev.atlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.atlas.documentos.UploadProperties;
import dev.atlas.fontes.openalex.OpenAlexProperties;

@SpringBootApplication
@EnableConfigurationProperties({UploadProperties.class, OpenAlexProperties.class})
public class AtlasApplication {

	public static void main(String[] args) {
		SpringApplication.run(AtlasApplication.class, args);
	}

}
