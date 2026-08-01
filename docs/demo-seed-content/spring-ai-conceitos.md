# Spring AI: conceitos fundamentais e como este projeto os usa

## O problema que o Spring AI resolve

Antes do Spring AI, integrar uma aplicação Java a um provedor de LLM
(OpenAI, Ollama, Anthropic, etc.) significava escrever um cliente HTTP
específico para a API de cada provedor — cada um com seu próprio formato de
requisição, autenticação e resposta. O Spring AI introduz um conjunto de
abstrações comuns (interfaces) que escondem essa diferença: o código de
aplicação depende só da abstração, e trocar de provedor vira uma questão de
configuração (qual starter está no classpath, qual `base-url`/chave está
configurada), não de reescrever lógica de negócio.

## As três abstrações centrais

**`ChatModel`**: representa "um modelo capaz de gerar texto a partir de uma
conversa". A implementação concreta (`OllamaChatModel`, `OpenAiChatModel`,
etc.) sabe como falar com aquele provedor específico, mas o código que a
usa só conhece a interface. Isso é o que permite, por exemplo, ter Ollama
rodando localmente e um servidor OpenAI-compatível (como LM Studio) ao
mesmo tempo, selecionáveis por requisição — cada um vira um bean
`ChatModel` distinto, qualificado por nome.

**`EmbeddingModel`**: transforma texto em um vetor numérico de alta
dimensão que captura significado semântico — dois textos com significado
parecido geram vetores próximos no espaço vetorial, mesmo que usem palavras
diferentes. É essa propriedade que faz a busca por similaridade (não por
palavra-chave exata) funcionar. Trocar de modelo de embedding, no entanto,
não é transparente como trocar de `ChatModel`: cada modelo produz vetores
de uma dimensão específica (768, 1024, etc.), então o esquema do banco
vetorial precisa combinar exatamente com o modelo usado para gerar os
vetores já armazenados.

**`VectorStore`**: abstrai o banco de dados vetorial em si (pgvector,
Redis, Pinecone, etc.) atrás de uma interface comum de
adicionar/buscar/remover documentos. Combinado com um `EmbeddingModel`, é a
base de qualquer pipeline de RAG (retrieval-augmented generation): o texto
de entrada vira vetor, o vetor é comparado contra os já armazenados, e os
mais próximos voltam como contexto para o modelo de chat gerar uma resposta
fundamentada.

## `ChatClient`: a API fluente por cima do `ChatModel`

`ChatClient` é uma camada de conveniência sobre `ChatModel`, com uma API
fluente (`.prompt().system(...).user(...).call().content()`) que facilita
compor um prompt com mensagens de sistema, histórico de conversa e
parâmetros de geração (temperatura, modelo específico, etc.) sem construir
objetos de requisição manualmente. Quando existe mais de um `ChatModel` no
classpath ao mesmo tempo, a auto-configuração do Spring Boot para
`ChatClient.Builder` desiste de escolher automaticamente (não há como saber
qual candidato é o certo) — nesse caso, cada `ChatClient` precisa ser
construído explicitamente a partir do `ChatModel` desejado, referenciado
por nome via `@Qualifier`.

## RAG na prática: retrieval antes de generation

Um erro comum ao pensar em RAG é assumir que o modelo de linguagem "sabe"
onde estão as respostas. Na prática, a etapa de retrieval é inteiramente
separada da geração: primeiro a pergunta do usuário vira um vetor, esse
vetor busca no `VectorStore` os trechos de documento mais parecidos
semanticamente, e SÓ ENTÃO esses trechos entram no prompt como contexto
para o modelo gerar uma resposta. Isso tem uma consequência importante:
citações de fonte podem vir diretamente do resultado da busca vetorial
(qual documento, qual trecho, qual score de similaridade), sem precisar
confiar que o modelo "lembrou" corretamente de onde tirou a informação —
uma fonte comum de alucinação em sistemas que pedem para o próprio modelo
citar suas fontes de memória.

Busca híbrida combina a busca vetorial (que captura significado) com busca
textual tradicional (que captura correspondência exata de termos, siglas,
nomes próprios) — um erro comum é achar que busca vetorial pura já resolve
tudo; ela erra sistematicamente em buscas por termos exatos que não têm
sinônimo semântico óbvio. Fundir os dois rankings (por exemplo, via
reciprocal rank fusion) costuma superar qualquer um dos dois isolado, sem
custo adicional de chamada ao modelo de linguagem.
