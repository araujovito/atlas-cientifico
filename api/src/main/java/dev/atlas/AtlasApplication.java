package dev.atlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.atlas.documentos.UploadProperties;
import dev.atlas.fontes.openalex.OpenAlexProperties;
import dev.atlas.documentos.ProcessamentoProperties;
import dev.atlas.documentos.PdfWorkerMain;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties({UploadProperties.class, OpenAlexProperties.class, ProcessamentoProperties.class, dev.atlas.acesso.PoliticaContas.class, dev.atlas.acesso.LimitesProperties.class, dev.atlas.documentos.CotasProperties.class})
public class AtlasApplication {

	public static void main(String[] args) {
		if (args.length > 0 && "--atlas-pdf-worker".equals(args[0])) {
			try { PdfWorkerMain.executar(java.util.Arrays.copyOfRange(args,1,args.length)); }
			catch (Exception e) { System.exit(2); }
			return;
		}
		SpringApplication.run(AtlasApplication.class, args);
	}

}
