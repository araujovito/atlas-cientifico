package dev.atlas.documentos;
import java.io.IOException;
public class PrazoPdfExcedidoException extends IOException {
    public PrazoPdfExcedidoException() { super("O PDF excedeu o tempo permitido para extração."); }
}
