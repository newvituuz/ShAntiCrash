# ShAntiCrash Free

Plugin para servidores Minecraft Java que protege contra crashes, exploits e pacotes malformados. Inspeciona cada
pacote antes de o servidor decodificá-lo, na thread de rede, e descarta o que for perigoso sem depender do tick.

Não é um anticheat: não julga como o jogador se move ou luta, só se os pacotes são íntegros.

**Plataformas:** Spigot, Paper, Purpur e Folia. **Versões:** do 1.8 ao 26.3. Veja o que foi efetivamente testado em
[docs/TESTES.md](docs/TESTES.md).

## Instalação

1. Coloque `ShAntiCrash-Free.jar` na pasta `plugins`. Não é preciso instalar o PacketEvents: ele vem dentro do JAR.
2. Reinicie o servidor. Evite `/reload`.
3. Ajuste `plugins/ShAntiCrash/config.yml` se precisar e aplique com `/shanticrash reload`.

Os padrões foram pensados para funcionar sem ajustes, mas foram avaliados com bots, não com clientes reais. Na primeira
semana, acompanhe os alertas e o arquivo em `plugins/ShAntiCrash/logs` antes de confiar nas punições. Para observar sem
desconectar ninguém, use `punishments.enabled: false`.

## O que ele protege

| Categoria | Exemplos |
|---|---|
| NETTY | Pacotes gigantes, estruturas malformadas, volume de dados, excesso de pacotes no login |
| ITEM | NBT profundo, muitas tags, arrays e strings enormes, itens dentro de itens, texto complexo demais |
| WINDOW | Slots e janelas inválidos, modos de clique inexistentes, listas de hashes abusivas |
| CREATIVE | Pacote criativo fora do modo criativo, slots inválidos, itens grandes |
| FLOOD | Limite por tipo de pacote, limite global, limite para pacotes grandes |
| PLACE | Face, mão, posição e cursor inválidos em colocação, quebra e interação |
| POSITION | NaN, Infinity, coordenadas fora do mundo, tamanho incorreto |
| PAYLOAD | Mensagens gigantes, canais inválidos, abuso de registro de canais |
| BOOK | Livros, placas e nomes na bigorna grandes demais |
| TAB | Sugestões longas, padrões conhecidos como `//calc`, seletores aninhados |
| COMMAND | Comandos conhecidos por travar servidores, caracteres ilegais, argumentos excessivos |
| RECIPE | Identificadores e janelas inválidos |
| RISK | Conexões com violações estruturais repetidas |
| CONEXÃO | Conexões simultâneas por IP, com detecção de BungeeCord e Velocity |

Cada um dos 68 checks pode ser configurado separadamente. Veja [docs/CHECKS.md](docs/CHECKS.md).

## Recursos

- **Modos** `STRICT`, `BALANCED`, `COMPATIBILITY` e `MONITOR_ONLY`, que mudam só os checks de taxa e volume. Pacotes
  estruturalmente inválidos são bloqueados em qualquer modo.
- **Risco por conexão** com decaimento: aperta os limites de taxa de quem acumula violações e só desconecta com evidência
  estrutural.
- **Janelas curtas de flood** opcionais, de 50 ms a 60 s, por grupo de pacote.
- **Profiler** por jogador (`/shanticrash profile`) e estatísticas gerais (`/shanticrash stats`).
- **Alertas no Discord** por webhook, com agrupamento, limite por minuto e fila limitada.
- **Sanitização de saída** opcional: limita lore, nomes e itens aninhados enviados ao cliente sem mudar o item no
  servidor.
- **API e eventos** para outros plugins. Veja [docs/PERMISSOES.md](docs/PERMISSOES.md).

## Como funciona

- **Antes do servidor:** o plugin vê o pacote antes da decodificação vanilla, que é onde a maioria dos exploits causa
  dano. Isso não é possível com eventos do Bukkit.
- **Do mais barato ao mais caro:** tamanho, frequência, layouts fixos e, por último, itens. O primeiro problema encerra
  a análise.
- **Sem recursão e sem alocar no caminho comum:** o NBT é validado nos próprios bytes do pacote, com pilha explícita e
  limitada.
- **Nada bloqueia a rede:** log em thread própria com fila limitada, comandos de punição em fila executada pelo
  agendador, permissões copiadas para campos ao entrar. No Folia, nenhuma thread de rede toca em entidades.
- **Uma violação não pune:** cada check tem VL com decaimento e, onde há causa inocente possível, buffer. Pacotes
  estruturalmente inválidos são descartados na hora, mas a punição continua dependendo do VL.

## Integrações

| Integração | Comportamento |
|---|---|
| ViaVersion, ViaBackwards, ViaRewind | O plugin vê os pacotes já traduzidos para o formato do servidor. Clientes muito antigos recebem limites de flood um pouco maiores. |
| Geyser, Floodgate | Identificados pela API desses plugins, nunca por nome ou UUID, que poderiam ser forjados. Recebem um multiplicador nos limites de flood, e cada check pode ser isentado com `exempt-bedrock`. |
| ProtocolLib | Não é necessário. Pode estar instalado junto. |

## Compilar

Requer JDK 11 ou mais novo e Maven. O bytecode gerado é Java 8, então o mesmo JAR roda em todas as versões suportadas.

```
mvn package
```

O resultado fica em `target/ShAntiCrash-Free.jar`. Esse comando também executa os testes unitários.

Para testar em servidores reais, veja [docs/TESTES.md](docs/TESTES.md).

## Documentação

- [Checks, limites e justificativas](docs/CHECKS.md)
- [Permissões, comandos e API](docs/PERMISSOES.md)
- [Testes realizados e resultados](docs/TESTES.md)
- [Mudanças por versão](CHANGELOG.md)
- [Licença e terceiros](NOTICE.md)

## Licença

GPL-3.0, por embutir o PacketEvents. Detalhes em [NOTICE.md](NOTICE.md).
