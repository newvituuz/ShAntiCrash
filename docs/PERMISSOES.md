# Permissões e comandos

## Permissões

| Permissão | Padrão | O que libera |
|---|---|---|
| `shanticrash.admin` | op | Todos os subcomandos. Inclui as permissões abaixo, exceto bypass |
| `shanticrash.alerts` | op | Receber alertas e usar `/shanticrash alerts` |
| `shanticrash.alerts.printer` | op | Avisos de entrada e saída do modo printer |
| `shanticrash.debug` | op | `/shanticrash debug` |
| `shanticrash.reload` | op | `/shanticrash reload` |
| `shanticrash.bypass` | ninguém | Ignora todos os checks, se `general.bypass-enabled` estiver ligado |

O bypass exige duas coisas: a opção ligada e a permissão. Mesmo um op não o recebe sem que alguém a conceda
explicitamente, porque quem tem bypass pode travar o servidor como qualquer outro cliente.

As permissões são lidas ao entrar e depois de `/shanticrash reload`. Mudou uma permissão com o jogador online? Recarregue
ou peça para ele entrar de novo. Isso existe porque as threads de rede não podem consultar a entidade do jogador.

## Comandos

Comando principal `/shanticrash`, com o atalho `/sac`.

| Comando | Permissão | Descrição |
|---|---|---|
| `/shanticrash help` | qualquer uma das acima | Lista os comandos |
| `/shanticrash info` | admin | Plataforma, integrações e contadores |
| `/shanticrash stats` | admin | Pacotes, cancelados, erros internos, conexões, risco, memória aproximada e Discord |
| `/shanticrash profile <jogador>` | debug | Inicia um perfil de pacotes e checks do jogador, que termina sozinho |
| `/shanticrash profile stop` | debug | Encerra os perfis ativos e mostra os relatórios |
| `/shanticrash version` | admin | Versão do plugin |
| `/shanticrash reload` | reload | Recarrega configuração e mensagens, mantendo os VLs |
| `/shanticrash alerts` | alerts | Alterna seus alertas |
| `/shanticrash alerts on\|off` | alerts | Liga ou desliga seus alertas |
| `/shanticrash alerts categoria <nome>` | alerts | Silencia ou reativa uma categoria, para você |
| `/shanticrash check <jogador>` | admin | Versão do cliente e estado de um jogador |
| `/shanticrash violations <jogador>` | admin | VLs e últimas violações de um jogador |
| `/shanticrash debug <jogador>` | debug | Registra no log cada pacote do jogador |
| `/shanticrash checks` | admin | Lista os checks; ativos em verde |

Com `general.hidden-command: true`, quem não tem permissão recebe a mensagem de comando desconhecido.

## Placeholders

**Em alertas e nos comandos de punição:** `%player%`, `%check%`, `%type%`, `%vl%`, `%max-vl%`, `%description%`,
`%infos%`.

Em comandos de punição, `%infos%` passa por um filtro, pois pode conter valores vindos de um pacote: só letras, números,
espaço e `. - = _` são mantidos. Mesmo assim, evite usá-lo em comandos que interpretam argumentos.

## Profiler

`/shanticrash profile <jogador>` mede, só para aquele jogador e só durante a sessão:

- pacotes e bytes por segundo, e os tipos mais frequentes;
- checks executados, tempo médio de cada um e violações;
- pacotes cancelados;
- tempo gasto **no plugin** por pacote, em p50, p95 e p99. Não é a latência de rede;
- memória aproximada do estado de proteção da conexão.

A sessão dura `profiler.duration` segundos (60) e se desliga sozinha; o relatório vai para quem iniciou, ou para o
console. `profiler.sample-every` mede o tempo de 1 a cada N pacotes. Nenhum conteúdo de pacote é guardado. Sem
sessão ativa, o custo é uma leitura de campo por pacote.

## API para outros plugins

```java
ShAntiCrashAPI api = ShAntiCrashAPI.get();            // null se o plugin não estiver ativo
ShAntiCrashAPI.ProtectionStatus status = api.status(); // versão, modo, checks ativos, conexões
ShAntiCrashAPI.CheckInfo info = api.check("flood.packet-limits");
ShAntiCrashAPI.ViolationSummary summary = api.summary(uuid); // VLs, total de flags e nível de risco
api.setPrinterMode(uuid, true);                       // aumenta o limite de colocação do jogador
Map<String, Double> vls = api.violations(uuid);
boolean bedrock = api.isBedrock(uuid);

ShAntiCrashAPI.Integration integration = api.registerIntegration(plugin, "minigames");
integration.exemptBehavioral(uuid, true);             // só checks de taxa e volume
integration.unregister();                             // desfaz todas as isenções desta integração
```

Pode ser chamada de qualquer thread. Os objetos devolvidos são cópias imutáveis. Uma integração **não consegue**
desligar checks estruturais: a isenção vale só para `flood.*`, `netty.byte-rate` e `risk.critical`.

## Eventos

| Evento | Quando | Thread | Cancelável |
|---|---|---|---|
| `ShAntiCrashViolationEvent` | Uma violação gerou alerta | Rede (assíncrono) | Não |
| `ShAntiCrashPacketBlockedEvent` | Um pacote foi descartado. No máximo 1 por segundo por conexão | Rede (assíncrono) | Não |
| `ShAntiCrashPunishmentEvent` | Antes de aplicar uma punição | Rede (assíncrono) | Só para checks comportamentais |

Os eventos são disparados como assíncronos quando vêm da thread de rede, que é o caso normal. Listeners não podem
tocar no mundo nem em entidades diretamente: agende o trabalho no scheduler da entidade ou da região, principalmente
no Folia. Um listener lento atrasa a conexão daquele jogador. Se nenhum plugin escuta um evento, ele nem é criado.
