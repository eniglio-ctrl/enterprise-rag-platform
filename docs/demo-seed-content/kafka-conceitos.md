# Apache Kafka: conceitos fundamentais

## O que Kafka é, e o que não é

Kafka é uma plataforma de streaming de eventos distribuída, frequentemente
descrita (de forma simplificada) como "uma fila de mensagens", mas isso
esconde uma diferença importante: numa fila tradicional (RabbitMQ, SQS), a
mensagem geralmente desaparece depois de consumida. No Kafka, mensagens
publicadas num tópico ficam retidas por um período configurável
(independente de terem sido lidas ou não), e múltiplos consumidores
independentes podem ler a mesma mensagem sem interferir uns nos outros —
o consumo não é destrutivo. Isso torna Kafka mais parecido com um log
distribuído e imutável do que com uma fila no sentido clássico.

## Tópicos, partições e ordem

Um tópico é dividido em partições — cada partição é um log ordenado e
append-only (só é possível adicionar ao final, nunca modificar ou remover
do meio). A ordem de mensagens só é garantida DENTRO de uma partição, nunca
entre partições diferentes do mesmo tópico. Isso é uma decisão de design
deliberada: paralelizar a escrita e a leitura entre partições é o que dá a
Kafka sua escalabilidade horizontal, mas em troca, qualquer caso de uso que
dependa de ordem estrita entre eventos de entidades diferentes precisa
garantir que esses eventos caiam na mesma partição (tipicamente
particionando por uma chave, como um ID de usuário ou pedido) — eventos com
a mesma chave sempre vão para a mesma partição, preservando ordem relativa
entre eles especificamente.

## Produtores, consumidores e grupos de consumo

Um producer publica mensagens num tópico sem se importar com quem vai
consumir. Consumers se organizam em "consumer groups" — dentro de um mesmo
grupo, cada partição é consumida por exatamente um membro do grupo por vez
(balanceamento automático de carga entre os membros), mas grupos diferentes
recebem cópias independentes de todas as mensagens. Isso é o que permite,
por exemplo, um mesmo evento de "pedido criado" ser consumido
simultaneamente por um serviço de notificação E por um serviço de
faturamento, sem que um interfira no outro, e ainda assim escalar
horizontalmente cada um desses serviços adicionando mais instâncias ao
mesmo grupo.

## Brokers e replicação

Um cluster Kafka é formado por múltiplos brokers (servidores). Cada
partição tem um broker "líder" que atende todas as leituras e escritas
daquela partição, e réplicas em outros brokers que apenas copiam os dados
do líder — se o líder cair, uma réplica assume automaticamente. O fator de
replicação (tipicamente 3 em produção) é o principal mecanismo de
tolerância a falhas: perder um broker não significa perder dados, desde que
pelo menos uma réplica estivesse em dia.

## Garantias de entrega

Kafka permite configurar diferentes níveis de garantia: "at most once"
(pode perder mensagem, nunca duplica), "at least once" (nunca perde, mas
pode duplicar em cenários de falha/retry) e "exactly once" (nem perde nem
duplica, com custo de coordenação adicional). Na prática, "at least once"
combinado com processamento idempotente no lado do consumidor (ex:
verificar se um ID de evento já foi processado antes de agir sobre ele) é
o padrão mais comum, porque "exactly once" de verdade, ponta a ponta, é
significativamente mais caro e complexo de garantir corretamente.

## Onde Kafka se encaixaria numa arquitetura como a deste projeto

Um cenário de uso concreto e comum: desacoplar a etapa de ingestão de
documentos (upload, extração de texto, chunking) da etapa de geração de
embeddings, publicando um evento "documento recebido" que um worker
consome de forma assíncrona — em vez do usuário esperar a requisição HTTP
inteira até o embedding terminar. Isso troca simplicidade (uma chamada
síncrona é mais fácil de raciocinar e depurar) por resiliência e
escalabilidade independente entre as duas etapas — uma decisão que só vale
a pena quando o volume real justifica a complexidade operacional adicional
de rodar e monitorar um cluster Kafka.
