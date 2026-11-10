# Checks do ShAntiCrash

São 68 checks, todos configuráveis em `checks.<categoria>.<tipo>` no `config.yml`. O formato de cada bloco está no
início desse arquivo. O alerta mostra `%check%` (categoria) e `%type%` (tipo).

**Estruturais e comportamentais.** Um check estrutural recusa o que um cliente funcionando não envia (NaN, slot
inexistente, NBT fundo demais). Um comportamental mede taxa ou volume (`flood.*`, `netty.byte-rate`, `risk.critical`).
Só os comportamentais mudam com `protection.mode` e com o nível de risco da conexão; os estruturais valem sempre, em
qualquer modo.

## Modos de proteção

| Modo | Limites de taxa | Punição dos comportamentais | Descarte dos comportamentais |
|---|---|---|---|
| `STRICT` | 25% menores | VL de punição × 0,75 | sim |
| `BALANCED` | valores do arquivo | sem mudança | sim |
| `COMPATIBILITY` | 50% maiores | VL de punição × 1,5 | sim |
| `MONITOR_ONLY` | valores do arquivo | só alerta | não |

Nenhum modo aumenta limites com base no que os jogadores fazem: os fatores são fixos, então ninguém consegue "treinar"
o plugin a aceitar mais.

**Como ler as tabelas:** "Punir em" é o VL que dispara a ação padrão. Com o decaimento padrão, o VL cai 1 a cada 5
minutos. "Ação" é o que acontece nesse VL; o pacote violador é descartado antes disso, exceto onde indicado.

## Ordem de execução

Do teste mais barato para o mais caro. O primeiro check que descarta encerra a análise do pacote.

1. Tamanho do frame e volume de dados
2. Limite de frequência
3. Layouts fixos: posição, janela, colocação, receita
4. Textos e mensagens, medidos pelos prefixos de tamanho
5. Permissão de modo criativo
6. Análise de itens, a única parte cara

## NETTY

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `frame-size` | Pacote maior que o limite do estado da conexão | 1 | KICK |
| `small-packet` | Pacote de layout fixo com mais de 2048 bytes | 3 | KICK |
| `pre-play-flood` | Excesso de pacotes antes de entrar no jogo | 1 | DROP |
| `connection-state` | Handshake ou login repetido na mesma conexão | 1 | DROP |
| `byte-rate` | Mais de 2 MiB/s sustentados | 3 | KICK |
| `malformed` | Pacote que não segue o formato do protocolo | 3 | KICK |
| `decode-failure` | Item que o decodificador não leu. Pode ser mod ou versão, por isso só alerta | 20 | ALERT |
| `processing-time` | Pacote lento, com monitoramento ligado | 20 | ALERT |

**Limites de frame:** até o 1.12 são 65536 bytes em jogo, porque itens e mensagens são menores nessas versões; do 1.13
em diante, 262144. Login e configuração usam 1 MiB, o limite do próprio protocolo para respostas de login, que mods
grandes realmente usam.

**Limitação:** a descompressão acontece antes do plugin ver o pacote. O limite de tamanho descomprimido contra bombas
de compressão é o do próprio servidor.

**Pacotes com ID desconhecido** nem chegam aos checks: o PacketEvents os recusa e fecha a conexão.

## FLOOD

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `global` | Mais de 800 pacotes/s somando tudo, rajada de 2500 | 3 | KICK |
| `big-frames` | Mais de 8 pacotes/s de 4096 bytes ou mais, rajada de 40 | 3 | KICK |
| `packet-limits` | Limite por tipo de pacote, tabela abaixo | 6 | KICK |

Cada conexão tem um token bucket por grupo. Uma negação isolada não é violação: um cliente que trava por um instante
envia tudo acumulado de uma vez. Só contam 20 negações dentro de 1 segundo, esquecidas depois de 5 segundos sem
negações.

| Grupo | Por segundo | Rajada | Descarta | Motivo do valor |
|---|---|---|---|---|
| `MOVEMENT` | 100 | 400 | não | Clientes enviam até 20/s. Descartar causaria puxões. |
| `VEHICLE` | 100 | 400 | não | Mesmo caso do movimento. |
| `CLICK_WINDOW` | 60 | 200 | sim | Mods de inventário clicam muito em sequência. |
| `CLICK_WINDOW_BUTTON` | 15 | 45 | sim | Ação humana, em telas como encantamento. |
| `CLOSE_WINDOW` | 15 | 45 | sim | |
| `CREATIVE_SLOT` | 50 | 150 | sim | Restaurar uma barra salva envia vários. |
| `BLOCK_PLACE` | 40 | 120 | não | Multiplicado por 3 no modo printer. |
| `USE_ITEM` | 40 | 120 | não | |
| `DIGGING` | 60 | 200 | não | Quebra instantânea em criativo. |
| `INTERACT_ENTITY` | 40 | 120 | não | |
| `ANIMATION` | 40 | 120 | não | |
| `HELD_ITEM_CHANGE` | 60 | 180 | não | Rolar a barra rapidamente. |
| `PLUGIN_MESSAGE` | 60 | 400 | sim | Mods enviam muitas mensagens ao conectar. |
| `EDIT_BOOK` | 2 | 8 | sim | |
| `UPDATE_SIGN` | 5 | 15 | sim | |
| `NAME_ITEM` | 20 | 60 | sim | Um por tecla digitada na bigorna. |
| `TAB_COMPLETE` | 20 | 60 | sim | Um por tecla digitada. |
| `CHAT` | 10 | 30 | sim | |
| `CRAFT_RECIPE` | 15 | 45 | sim | |
| `RECIPE_BOOK` | 15 | 45 | sim | |
| `KEEP_ALIVE` | 120 | 400 | não | Inclui pong, usado por anticheats. |
| `CLIENT_TICK` | 100 | 400 | não | Um por tick. |
| `ENTITY_ACTION` | 40 | 120 | não | |
| `COMMAND_BLOCK` | 5 | 15 | sim | |
| `OTHER` | 100 | 400 | não | Pacotes sem limite próprio. |

Estes valores vêm da taxa de ticks do cliente e do que mods de inventário e construção fazem. **Não foram medidos com
clientes reais**, então podem precisar de ajuste no seu servidor.

### Janelas curtas

Opcionais, em `checks.flood.packet-limits.options.advanced-rules`, desligadas por padrão
(`advanced-rules-enabled: false`). Cada grupo aceita até 4 janelas exatas, de 50 ms a 60 s, com até 512 eventos e
filtro opcional por tamanho do pacote (`min-bytes`, `max-bytes`). Elas pegam rajadas que o token bucket deixa passar,
como 30 cliques em 80 ms.

| Ação | O que faz |
|---|---|
| `DROP` | Descarta o pacote e conta VL |
| `FLAG` | Só conta VL |
| `COUNT` | Só conta, sem VL |

Custo: memória fixa por conexão (um `long` por evento permitido na janela) e tempo constante por pacote. Sob flood
contínuo, o VL é registrado no máximo duas vezes por segundo. Comece com `FLAG` e acompanhe os logs antes de usar
`DROP`.

## RISK

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `critical` | Pontuação de risco crítica **e** evidência estrutural: 5 violações estruturais em 30 s | 1 | KICK |

Cada conexão tem uma pontuação que sobe a cada violação (3 pontos se estrutural, 1 se comportamental) e cai pela metade
a cada 60 s. Os níveis são baixo, moderado (10), alto (25) e crítico (50).

| Nível | Efeito |
|---|---|
| Baixo | Nenhum além do registro |
| Moderado | Limites de taxa × 0,75 até a pontuação voltar abaixo de 10 |
| Alto | Nenhum além do registro; os pacotes inválidos já são descartados pelos próprios checks |
| Crítico | `risk.critical`, só com evidência estrutural. Flood sozinho nunca chega aqui |

Tudo em `risk` no `config.yml`. O nível aparece em `/shanticrash check <jogador>`, em `/shanticrash stats` e na API.

## WINDOW

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `invalid-slot` | Slot fora da janela. O tamanho vem do que o servidor enviou ao cliente | 5 | KICK |
| `invalid-id` | ID de janela fora de 0 a 255 | 3 | KICK |
| `invalid-mode` | Combinação de modo e botão que o protocolo não define | 5 | KICK |
| `mismatch` | Cliques repetidos em janela que não está aberta. Não descarta | 20 | ALERT |
| `drag-sequence` | Arrasto fora de ordem ou com mais de 512 slots. Não descarta | 20 | ALERT |
| `hashed-stacks` | No 1.21.5+, listas de hashes acima dos limites | 3 | KICK |
| `simple-packets` | Botão, fechar e confirmação com conteúdo inválido | 5 | KICK |

## ITEM

`analysis` lê os itens dos pacotes de clique, criativo e colocação 1.8. O resultado é reportado pelos checks abaixo,
cada um com seu próprio interruptor. Desligar um deles remove o limite correspondente. Os valores estão em
`item-protection`.

| Tipo | Limite padrão | Justificativa |
|---|---|---|
| `nbt-depth` | 32 níveis | O protocolo aceita 512. Itens reais raramente passam de 16. |
| `nbt-nodes` | 32768 tags por pacote | Um baú cheio de itens encantados tem cerca de 3 mil. |
| `string-length` | 32767 bytes | O maior texto que o próprio cliente gera. |
| `array-length` | 16384 elementos | O tamanho dos dados de um mapa, o maior array de uso normal. |
| `list-length` | 1024 elementos | Baús têm 27 e lore tem até 256. Limites menores por componente abaixo. |
| `item-bytes` | 131072 bytes | Um shulker de livros grandes chega perto disso. |
| `items-per-packet` | 1458 | 54 slots com um shulker de 27 em cada. |
| `item-depth` | 4 níveis | Item, baú, shulker, conteúdo. |
| `component-count` | 64 | Há pouco mais de 90 tipos de componente no jogo. |
| `text-complexity` | 16 `translate`, 1024 `extra`, 64 níveis, 4096 nós | Formas que tornam texto serializado caro. Gradientes usam muitos `extra`. |
| `amount` | 0 a 127 | O maior valor do campo no protocolo antigo. |
| `book-content` | 100 páginas, 3072 bytes por página | Limite do cliente, no pior caso em UTF-8. |
| `changed-slots` | 128 | O limite do próprio jogo. |
| `processing-time` | 200 ms | Só alerta, salvo se a análise for interrompida. |

Todos punem em VL 3 com KICK, exceto `processing-time`, que só alerta.

**Limites por componente (1.20.5+),** reportados por `list-length` ou `string-length`:

| Componente | Opção | Padrão | Justificativa |
|---|---|---|---|
| `container` | `max-container-items` | 96 | Um shulker tem 27 slots |
| `bundle_contents` | `max-bundle-items` | 64 | O bundle cabe no máximo 64 itens |
| `charged_projectiles` | `max-charged-projectiles` | 16 | Multishot carrega 3 |
| `lore` | `max-lore-lines` | 256 | Limite do próprio jogo |
| `can_break`, `can_place_on` | `max-predicates` | 64 | Blocos e propriedades somados |
| `tool` | `max-tool-rules` | 96 | Ferramentas do jogo usam poucas regras |
| `profile` | `max-profile-properties` e `max-profile-bytes` | 16 e 8192 | Uma skin assinada tem 2 a 3 KiB |

**Orçamento de texto por pacote:** além do limite de nós por texto, `text.max-total-nodes` (16384) soma os nós de todos
os textos de um pacote, para que muitas lores médias não custem o mesmo que uma gigante. Páginas de livro, inclusive a
versão filtrada, são medidas em bytes UTF-8.

### Como a análise funciona

- **Antes do 1.20.5:** o NBT é validado diretamente nos bytes do pacote, sem criar objetos. A pilha é explícita e
  limitada pela profundidade configurada, então aninhamento hostil não estoura a pilha da thread nem aloca memória.
- **Do 1.20.5 em diante:** a estrutura que pode se aninhar (`container`, `bundle_contents`, `charged_projectiles`,
  `use_remainder`, `food` e `pot_decorations` nas versões em que contêm itens) é percorrida com pilha explícita, e os
  limites são testados antes de descer um nível. O PacketEvents só lê os valores que não podem conter itens, usando um
  wrapper que recusa coleções maiores que o próprio pacote. Sem isso, um contador de poucos bytes poderia reservar
  gigabytes.
- **Do 1.21.5 em diante:** cliques trazem só hashes, e as listas são validadas por `window.hashed-stacks`.

### `decoding-guard` e `disable-item-decoding`

| Modo | Cliques | Slot criativo |
|---|---|---|
| `NONE` | Sem alteração | Sem alteração |
| `SMART` (padrão) | Itens de 2048 bytes ou mais perdem os dados | Sem alteração |
| `CREATIVE` | Sem alteração | Só os dados permitidos são mantidos |
| `ALL` | Todos os itens perdem os dados | Só os dados permitidos são mantidos |

**Por que alterar cliques é seguro:** os itens de um clique são só a opinião do cliente sobre o resultado. O servidor
calcula o resultado real, compara e reenvia a janela se discordar. O pior caso é uma atualização extra de janela, sem
item perdido ou duplicado.

**Por que o slot criativo é diferente:** nele o item é a própria fonte dos dados. `CREATIVE` e `ALL` realmente apagam o
que não está na lista, como conteúdo de baús e livros. Use apenas se os jogadores criativos não precisam disso.

**Limitação:** a partir do 1.21.5, a parte dos cliques não faz nada, pois não há item para remover.

## CREATIVE

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `not-creative` | Pacote criativo fora do modo criativo, antes de qualquer decodificação | 5 | KICK |
| `invalid-slot` | Slot fora de -1 a 45 | 3 | KICK |
| `item-size` | Item acima de 65536 bytes | 3 | KICK |
| `invalid-amount` | Quantidade acima de 127 | 3 | KICK |
| `run-command` | Texto com `run_command`. Copiar uma placa pode causar isso | 5 | ALERT |

## PLACE

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `invalid-placement` | Face, mão, posição ou cursor inválidos | 5 | KICK |
| `legacy-header` | Face ou posição inválidas no formato do 1.8 | 5 | KICK |
| `invalid-use` | Mão ou rotação inválidas | 5 | KICK |
| `invalid-dig` | Ação, face ou posição inválidas | 5 | KICK |
| `invalid-interact` | Ação, mão ou alvo inválidos | 5 | KICK |
| `printer` | Detecta construção sustentada e aumenta o limite. Não pune | - | NONE |

O limite do cursor é propositalmente alto (1024): mods de colocação precisa guardam dados nele, bem fora de 0 a 1. Isto
não é um anticheat, então alcance e velocidade não são avaliados. A frequência está em `flood.packet-limits`.

`printer` vem com `automatic: false`. Quem ataca também consegue colocar blocos em velocidade alta, então só ligue se o
servidor permite printers. A alternativa é `ShAntiCrashAPI.setPrinterMode`.

## POSITION

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `format` | Tamanho diferente do exato para cada tipo de movimento | 1 | KICK |
| `invalid-numbers` | NaN ou Infinity | 1 | KICK |
| `bounds` | Além de 30.000.512 na horizontal ou 20.000.000 na vertical | 1 | KICK |
| `rotation` | Inclinação acima de 90,5 ou giro acima de 10.000.000 | 3 | KICK |

Nada aqui avalia distância ou velocidade. O giro tem limite alto porque cresce sem parar enquanto o jogador gira.

## PAYLOAD

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `size` | Mensagem acima de 32767 bytes, exceto canais em `exempt-channels` | 3 | KICK |
| `invalid-channel` | Nome de canal inválido ou com mais de 256 caracteres | 3 | KICK |
| `register` | Mais de 1024 canais em um pacote ou 4096 na conexão | 1 | KICK |
| `brand` | Marca acima de 256 bytes ou mais de 8 envios | 3 | KICK |
| `blocked-channel` | Canais escolhidos pelo administrador. Vazio por padrão | 5 | KICK |

Nenhum canal é bloqueado por padrão. Geyser, Floodgate, Fabric, Forge e WorldEdit usam nomes válidos e passam
normalmente.

## BOOK

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `content` | Do 1.17.1 em diante: mais de 100 páginas, página acima de 3072 bytes, livro acima de 65536 bytes, título acima de 384 bytes | 3 | KICK |
| `legacy-item` | Antes do 1.17.1: livro acima de 65536 bytes. O conteúdo passa pela análise de itens | 3 | KICK |
| `sign-text` | Linha de placa acima de 1536 bytes | 3 | KICK |
| `item-name` | Nome na bigorna acima de 1024 bytes | 3 | KICK |

## TAB

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `structure` | Campos inválidos ou bytes sobrando | 5 | KICK |
| `length` | Texto acima de 8192 bytes. A tela do bloco de comando usa textos longos | 5 | KICK |
| `pattern` | `//calc`, `/to`, expressões como `for(` e mais de 32 colchetes | 10 | KICK |

## COMMAND

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `length` | Chat acima de 1024 bytes ou comando acima de 8192 bytes | 3 | KICK |
| `illegal-characters` | Caracteres de controle ou `§` | 3 | KICK |
| `blocked` | Comandos conhecidos por travar servidores com certos argumentos | 3 | KICK |
| `complexity` | Mais de 32 colchetes ou 256 argumentos | 5 | KICK |

A lista de `blocked` só tem as formas perigosas: `/mv ^` é bloqueado, `/mv list` não. Esvazie a lista se as versões dos
plugins que você usa já corrigiram esses problemas.

## RECIPE

| Tipo | Detecta | Punir em | Ação |
|---|---|---|---|
| `invalid-request` | Identificador, janela ou formato inválidos no pedido de receita | 1 | KICK |
| `invalid-displayed` | Identificador inválido na receita exibida | 1 | KICK |

## Mecânicas opcionais

Ficam em `mechanics`, são independentes dos checks e vêm desligadas porque alteram o jogo.

| Opção | Efeito |
|---|---|
| `nether-portal-delay` | Tempo mínimo entre usos de portal |
| `max-arrow-velocity` | Limita a velocidade de flechas |
| `shears-cooldown` | Tempo mínimo entre tosquias |
| `break-close-inventory` | Fecha o inventário de quem quebra um bloco |
| `interact-container-delay` | Tempo mínimo entre aberturas de contêineres |
| `redstone-throttle` | Limite de atualizações de redstone em trilhos, alçapões e comparadores, por chunk |

`redstone-throttle` é um limite genérico de volume. Não corrige nenhum exploit específico e não foi validado contra um.
Máquinas grandes de redstone podem ser afetadas.

As mecânicas não têm teste automatizado neste projeto: dependem de eventos de jogo que o bot de teste não reproduz.
