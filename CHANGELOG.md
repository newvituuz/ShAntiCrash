# Mudanças

## 2.0.0

### Proteção

- Modos de proteção `STRICT`, `BALANCED` (padrão), `COMPATIBILITY` e `MONITOR_ONLY` em `protection.mode`. Mudam só os
  checks de taxa e volume; os estruturais valem em qualquer modo.
- Pontuação de risco por conexão, com decaimento por meia-vida. No nível moderado, os limites de taxa ficam 25% mais
  baixos até a pontuação cair. Novo check `risk.critical`, que só desconecta com evidência estrutural (5 violações
  estruturais em 30 s por padrão).
- Limite de conexões simultâneas por IP (`connection.max-per-address`, padrão 10). Atrás de BungeeCord ou Velocity,
  o limite se desliga sozinho até `connection.trusted-proxies` ser configurado.
- Janelas curtas de flood opcionais: até 4 por grupo de pacote, de 50 ms a 60 s, com filtro por tamanho e ações `DROP`,
  `FLAG` e `COUNT`.
- Novos limites de componentes: `bundle_contents`, `lore`, `can_break`/`can_place_on`, `container`,
  `charged_projectiles`, `tool` e `profile`, além de um orçamento de nós de texto somado no pacote inteiro.
- Páginas de livro, inclusive o conteúdo filtrado, medidas em bytes UTF-8.

### Ferramentas

- `/shanticrash stats`: pacotes, cancelados, erros internos, conexões recusadas, distribuição de risco e memória
  aproximada.
- `/shanticrash profile <jogador>` e `/shanticrash profile stop`: pacotes e bytes por segundo, tipos, checks executados
  e acionados, tempo no plugin em p50, p95 e p99 e memória da conexão. A instrumentação se desliga sozinha ao fim da
  sessão.
- `/shanticrash check` mostra o nível de risco do jogador.
- Alertas no Discord por webhook: envio em thread própria, agrupamento de eventos repetidos, limite por minuto, fila
  limitada e respeito ao 429. A URL nunca aparece em logs.
- Sanitização opcional de itens enviados ao cliente (`outgoing-sanitizer`), desligada por padrão. Atua só na cópia do
  pacote, nunca no item do servidor.

### API

- `status()`, `check(id)`, `checks()` e `summary(uuid)` devolvem cópias imutáveis.
- `registerIntegration(plugin, nome)`, cuja isenção vale só para checks de taxa e volume.
- Eventos `ShAntiCrashViolationEvent`, `ShAntiCrashPacketBlockedEvent` e `ShAntiCrashPunishmentEvent`.

### Correções e robustez

- O reload monta a configuração de todos os checks antes de aplicar qualquer uma.
- O contador de componentes presentes e ausentes não sofre mais overflow.
- Erros internos são contados por check e aparecem em `/shanticrash stats`.
- Fuzzing de todos os tipos de pacote em nove protocolos e de itens com componentes em quatro versões, sem erros
  internos.

## 1.0.0

Versão de lançamento.
