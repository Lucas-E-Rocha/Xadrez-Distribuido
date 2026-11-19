package br.furb.xadrez.partidas.api;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.furb.xadrez.partidas.dominio.FilaService;

@RestController
@RequestMapping("/api/fila")
public class FilaController {

    private final FilaService fila;

    public FilaController(FilaService fila) {
        this.fila = fila;
    }

    public record EntrarNaFila(String jogadorId, Integer aposta) {}

    /** R3: responde 202 Accepted com o id do ticket; o cliente consulta GET /api/fila/{id} depois. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> entrar(@RequestBody EntrarNaFila req) {
        UUID id = fila.entrar(req.jogadorId(), req.aposta() == null ? 0 : req.aposta());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/fila/" + id))
                .body(Map.of("ticketId", id, "status", "AGUARDANDO", "consultar", "/api/fila/" + id));
    }

    @GetMapping("/{ticketId}")
    public Map<String, Object> consultar(@PathVariable UUID ticketId) {
        return fila.consultar(ticketId);
    }
}
