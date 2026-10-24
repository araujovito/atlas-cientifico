package dev.atlas.documentos;
import java.io.IOException;
public class PdfProtegidoException extends IOException {
    public PdfProtegidoException() { super("PDF protegido por senha."); }
}
