package br.furb.xadrez.partidas.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.furb.xadrez.partidas.dominio.JogoService;

@RestController
@RequestMapping("/api/partidas")
public class PartidaController {

    private final JogoService jogo;

    public PartidaController(JogoService jogo) {
        this.jogo = jogo;
    }

    public record NovaJogada(String jogadorId, String origem, String destino, String promocao) {}

    public record Desistencia(String jogadorId) {}

    @GetMapping("/{id}")
    public Map<String, Object> estado(@PathVariable UUID id) {
        return jogo.estado(id);
    }

    @GetMapping("/{id}/movimentos")
    public List<String> movimentos(@PathVariable UUID id, @RequestParam String origem) {
        return jogo.movimentos(id, origem);
    }

    @PostMapping("/{id}/jogadas")
    public Map<String, Object> jogar(@PathVariable UUID id, @RequestBody NovaJogada j) {
        return jogo.jogar(id, j.jogadorId(), j.origem(), j.destino(), j.promocao());
    }

    @PostMapping("/{id}/desistir")
    public Map<String, Object> desistir(@PathVariable UUID id, @RequestBody Desistencia d) {
        return jogo.desistir(id, d.jogadorId());
    }
}
