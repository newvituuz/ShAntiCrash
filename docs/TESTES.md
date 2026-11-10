# Testes

O que foi testado, como, e o que ainda não foi. Última rodada: 09/11/2026, com o código desta versão.

## Resumo

| Tipo | Resultado |
|---|---|
| Testes unitários (`mvn package`) | 147 de 147 passaram |
| Integração em servidores reais | 18 de 18 servidores passaram em todos os cenários |
| Spigot (1.8.8 e 1.21.11) | **Não testado nesta rodada**, veja [Pendências](#pendências) |
| Clientes reais, ViaVersion, Geyser | **Não testado nesta rodada** |

Os bots seguem o protocolo, mas não são o cliente oficial. Os padrões do plugin foram calibrados com eles. Na primeira
semana em produção, acompanhe os alertas e `plugins/ShAntiCrash/logs` antes de confiar nas punições, como diz o
README.

## Testes unitários

Rodam em todo `mvn package`, sem servidor.

| Classe | Testes | O que cobre |
|---|---|---|
| `ChecksTest` | 77 | Cada check com pacote válido e inválido, em layouts de várias versões |
| `ViolationSystemTest` | 19 | VL, decaimento, multiplicador, buffer, punições e limites de flood |
| `NbtScannerTest` | 18 | Profundidade, nós, strings, arrays e listas de NBT, inclusive 200 mil níveis sem estouro de pilha |
| `PacketEventsItemReaderTest` | 13 | Itens com componentes (1.20.5+) lidos pelo PacketEvents |
| `DecodingGuardTest` | 10 | Compactação de itens pesados nos modos de `disable-item-decoding` |
| `EventLoggerTest` | 5 | Fila cheia descarta em vez de bloquear, rotação, linhas forjadas, remoção de arquivos antigos |
| `ConcurrencyTest` | 5 | Ataques simultâneos com estado separado, conexões que entram e saem sem vazar, limite de conexões |

## Integração em servidores reais

Cada servidor sobe com o JAR compilado, em `127.0.0.1` e modo offline, e bots executam os cenários abaixo. Um cenário de
ataque passa quando o check esperado aparece no log de eventos do plugin. Um cenário legítimo passa quando a conexão
continua aberta e nada é registrado para o bot. Ao fim, o servidor precisa estar vivo e sem conexões monitoradas
sobrando.

| Servidor | Build | Java | Cenários | Resultado |
|---|---|---|---|---|
| Paper 1.8.8 | 445 | 8 | 37 | OK |
| Paper 1.12.2 | 1620 | 8 | 38 | OK |
| Paper 1.16.5 | 794 | 11 | 38 | OK |
| Paper 1.18.2 | 388 | 17 | 38 | OK, com 1 repetição |
| Paper 1.19.4 | 550 | 17 | 38 | OK |
| Paper 1.20.4 | 499 | 17 | 38 | OK |
| Paper 1.20.6 | 151 | 21 | 38 | OK |
| Paper 1.21.4 | 232 | 21 | 38 | OK |
| Paper 1.21.11 | 132 | 21 | 37 | OK |
| Paper 26.1.2 | 74 | 25 | 37 | OK |
| Paper 26.2 | 135 | 25 | 37 | OK |
| Paper 26.3 | 245 | 25 | 37 | OK |
| Folia 1.20.4 | 31 (alpha) | 17 | 38 | OK |
| Folia 1.21.11 | 14 | 21 | 37 | OK |
| Folia 26.3 | 6 (alpha) | 25 | 37 | OK |
| Purpur 1.16.5 | mais recente | 11 | 38 | OK |
| Purpur 1.21.11 | mais recente | 21 | 37 | OK |
| Purpur 26.3 | mais recente | 25 | 37 | OK |

A partir do 1.21.5, o cenário `clique-item-pesado` não se aplica, por isso há 37 cenários em vez de 38. O Paper 1.8.8 e o
Purpur 1.16.5 se identificam como Spigot 1.8.8 e Paper 1.16.5 na linha de inicialização do plugin. Isso é o que esses
servidores informam e não muda nada no funcionamento.

### Cenários

- **Legítimos:** `trafego-normal`, `criativo-normal`, `clique-item-pesado`
- **POSITION:** `posicao-nan`, `posicao-infinita`, `posicao-fora-do-mundo`, `posicao-formato`, `rotacao-invalida`
- **NETTY:** `frame-gigante`, `frame-truncado`
- **ITEM:** `nbt-profundo`, `nbt-muitos-nos`, `nbt-string`, `nbt-array`, `nbt-lista`, `itens-aninhados`
- **CREATIVE:** `criativo-slot-invalido`, `sobrevivencia-criativo`
- **WINDOW:** `janela-slot-invalido`, `janela-modo-invalido`
- **FLOOD:** `flood-cliques`, `flood-movimento`
- **PAYLOAD:** `canal-invalido`, `payload-grande`, `registro-canais`, `marca-grande`
- **BOOK:** `livro-paginas`
- **TAB:** `tab-gigante`, `tab-padrao`
- **COMMAND:** `comando-bloqueado`, `comando-gigante`
- **RECIPE:** `receita-invalida`
- **Gerais:** `robustez` (200 conexões que morrem em todas as etapas), `alertas` (o operador recebe o alerta no chat),
  `reload`, `reload-invalido` (arquivo quebrado mantém a configuração anterior), `servidor-vivo`, `sem-vazamento`

## Como rodar

```
mvn package dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt" "-Dmdep.includeScope=test"
.\tools\run-integration.ps1 -Servers C:\testes\jars -Work C:\testes\work
```

Cada servidor fica em `<Servers>\<tipo>-<versão>.jar`, por exemplo `paper-1.20.4.jar`. Os caminhos dos JDKs estão no
início de `tools/run-integration.ps1`. Para rodar só alguns servidores ou cenários, use `-Only paper-26.3,folia-26.3`
e `-Scenarios nbt-profundo,frame-truncado`. Os resultados ficam em `target/integration`.

O script aceita a EULA do Minecraft para essas instâncias locais de teste.

## O que mudou nos testes nesta rodada

**Minecraft 26.3 e o `ClientTickEnd`.** No 26.3, o servidor desconecta quem envia uma segunda posição antes do
`ClientTickEnd`, o pacote que o cliente oficial manda ao fim de cada tick desde o 1.21.2. Os bots não mandavam esse
pacote e eram expulsos pelo próprio Minecraft com "Invalid move player packet received", sem nenhuma ação do plugin.
O layout do pacote de posição não mudou no 26.3 (3 doubles e 1 byte, conferido na classe
`ServerboundMovePlayerPacket$Pos` do servidor), então o check `position.format` continua correto. Mudanças:

- `trafego-normal` passou a enviar `ClientTickEnd` a partir do 1.21.2, como o cliente real.
- `flood-movimento` usa rotações em vez de posições a partir do 26.3. Uma rajada de posições é cortada pelo
  Minecraft antes de chegar ao limite do plugin. Rotações contam no mesmo grupo MOVEMENT, então o limite do plugin
  continua sendo testado.

**Repetição de cenário sem nenhum efeito.** Em rodadas completas, mais ou menos uma vez a cada várias, um bot que
entra logo depois de outro ter sido expulso faz o ataque e nada acontece: nenhum evento e conexão aberta. No Paper
1.12.2, um diagnóstico temporário mostrou que o pacote não chegou nem ao PacketEvents nem ao decodificador do
Minecraft, que teria desconectado o bot com "Internal Exception". Ou seja, o servidor não leu o pacote, e ele não
passou pela proteção. Não acontece com o cenário isolado e some quando o tempo muda, o que aponta para o ambiente
de teste. Quando acontece, o cenário é repetido uma vez com outro bot, e a saída mostra
`PASS <cenário> (2ª tentativa: ...)`. Na rodada final houve uma repetição, em `flood-movimento` no Paper 1.18.2. A
causa exata não foi isolada.

## Pendências

- **Spigot 1.8.8 e 1.21.11.** O Spigot não tem download direto, precisa ser compilado com o BuildTools. Para testar:

  ```
  java -jar BuildTools.jar --rev 1.8.8 --output-dir C:\testes\jars      (com Java 8)
  java -jar BuildTools.jar --rev 1.21.11 --output-dir C:\testes\jars    (com Java 21)
  ```

  Renomeie as saídas para `spigot-1.8.8.jar` e `spigot-1.21.11.jar` e rode com `-Only spigot-1.8.8,spigot-1.21.11`.
- **ViaVersion, ViaBackwards e ViaRewind.** O script suporta `-ExtraPlugins` e `-BotVersion` para isso, mas não
  foi rodado nesta rodada.
- **Geyser e Floodgate.** Precisam de um cliente Bedrock, que os bots não implementam.
- **Clientes oficiais e mods.** Só um teste com jogadores reais confirma que os limites não atingem clientes
  modificados comuns.
