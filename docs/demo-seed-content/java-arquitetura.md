# Java e arquitetura de software: conceitos fundamentais

## A JVM e o modelo de execução

Java é uma linguagem compilada para bytecode, não para código de máquina
nativo diretamente. O compilador `javac` transforma o código-fonte `.java`
em arquivos `.class` contendo bytecode, que roda sobre a Java Virtual
Machine (JVM). Essa camada de abstração é o que permite o lema "write once,
run anywhere" — o mesmo bytecode roda em qualquer sistema operacional que
tenha uma JVM compatível instalada.

A JVM moderna (a partir do HotSpot, usado pelo OpenJDK) usa compilação
just-in-time (JIT): o bytecode é interpretado inicialmente, e os trechos de
código executados com mais frequência ("hot paths") são compilados para
código de máquina nativo em tempo de execução, otimizando o desempenho ao
longo da vida do processo. Isso explica por que aplicações Java geralmente
ficam mais rápidas depois de alguns minutos rodando (o chamado "warm-up") —
diferente de linguagens compiladas ahead-of-time, que já começam no pico de
performance.

Gerenciamento de memória é automático via garbage collector (GC). O heap é
dividido tipicamente em gerações (jovem e velha), partindo do princípio de
que a maioria dos objetos morre jovem — um objeto recém-criado (ex: uma
variável local temporária) tem muito mais chance de virar lixo rapidamente
do que um objeto que já sobreviveu a vários ciclos de coleta. Diferentes
coletores (G1, ZGC, Shenandoah) fazem trade-offs diferentes entre pausa
(quanto tempo a aplicação "congela" durante a coleta) e throughput.

## Orientação a objetos e os quatro pilares

Encapsulamento, abstração, herança e polimorfismo continuam sendo a base
conceitual, mas o Java moderno (a partir da versão 8, e mais fortemente
desde a 17) incentiva composição sobre herança profunda. Interfaces com
métodos default, classes seladas (`sealed`) e records (Java 16+) dão
ferramentas melhores pra modelar dados imutáveis sem a verbosidade de
getters/setters/equals/hashCode escritos à mão — um record como
`record Ponto(int x, int y) {}` já gera tudo isso automaticamente.

## Padrões arquiteturais comuns em back-end Java

**Camadas (layered architecture)**: controller → service → repository é o
padrão mais comum em aplicações Spring — cada camada só conhece a
imediatamente abaixo dela, o que facilita testar cada uma isoladamente
(mockando a camada de baixo).

**Injeção de dependência (DI)**: em vez de uma classe criar suas próprias
dependências com `new`, elas são fornecidas de fora (por um construtor,
tipicamente). Isso inverte o controle de quem decide QUAL implementação
usar — um container (como o do Spring) resolve isso em tempo de execução,
o que permite trocar uma implementação real por um mock em teste sem mudar
o código de produção.

**Módulos e limites de bounded context**: em sistemas maiores, separar o
código em módulos Maven/Gradle distintos (não só pacotes dentro de um jar
só) força limites de dependência explícitos — um módulo "compartilhado" só
pode ser importado, nunca o contrário, evitando dependências circulares
que aparecem facilmente quando tudo vive num único módulo monolítico.

## Concorrência

O modelo de threads do Java sempre existiu, mas historicamente era caro
criar muitas threads (cada uma consome memória de stack real do sistema
operacional). O Java 21 introduziu virtual threads (Project Loom) — threads
gerenciadas pela própria JVM, muito mais baratas de criar, permitindo um
modelo de "uma thread por requisição" mesmo com dezenas de milhares de
requisições concorrentes, sem precisar reescrever o código para um modelo
reativo (que troca simplicidade de leitura por eficiência de recursos).
