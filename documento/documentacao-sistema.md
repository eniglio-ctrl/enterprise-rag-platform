# Documentação — enterprise-rag-platform

## 1. Visão geral

O **enterprise-rag-platform** é uma plataforma de RAG (Retrieval-Augmented Generation)
construída como um sistema corporativo real, e não como um notebook de tutorial:
microsserviços independentes em Java/Spring Boot, banco vetorial PostgreSQL + pgvector,
inferência local via Ollama (sem depender de API paga/externa), interface web, logs
estruturados, métricas, health checks e documentação de decisões de arquitetura (ADRs).

Na prática, o sistema permite:

1. Fazer upload de um documento (PDF, DOCX, Markdown ou texto puro).
2. O documento é automaticamente extraído, dividido em pedaços ("chunks"), transformado
   em vetores (embeddings) e indexado no PostgreSQL/pgvector — sem nenhuma ação manual
   adicional.
3. Fazer uma pergunta em linguagem natural sobre o conteúdo enviado e receber uma
   resposta gerada por LLM, **com citação exata da fonte** (arquivo, número do chunk e
   score de similaridade).
4. Pedir um **diagrama de arquitetura** com base no que foi enviado (ex.: uma transcrição
   de palestra descrevendo uma arquitetura AWS) e receber um diagrama Mermaid.js
   desenhado a partir dos componentes e do fluxo mencionados no texto.

## 2. Diagrama de arquitetura

![Arquitetura do enterprise-rag-platform](./diagrama-arquitetura.svg)

O sistema é composto por 5 peças rodando em containers Docker separados:

| Componente          | Papel                                                             | Porta |
|----------------------|--------------------------------------------------------------------|-------|
| `web-ui`             | Interface web (upload + chat) — HTML/CSS/JS puro servido por nginx | 3000  |
| `ingestion-service`  | Recebe upload, extrai texto, gera embeddings, grava no pgvector    | 8081  |
| `rag-service`        | Busca por similaridade, monta contexto, gera resposta com citações| 8082  |
| PostgreSQL + pgvector| Armazena os chunks e seus vetores de embedding                    | 5432  |
| Ollama               | Executa os modelos de IA localmente (embedding e chat)            | 11434 |

**Ponto importante sobre a indexação automática**: não existe um botão ou endpoint
separado de "indexar". A indexação no pgvector acontece dentro da própria requisição de
upload — o `ingestion-service` extrai o texto, gera o embedding via Ollama e grava no
banco antes mesmo de responder ao cliente. Assim que o upload retorna sucesso, o
conteúdo já está pesquisável.

## 3. Funcionalidades

- Upload de PDF, DOCX, Markdown e texto puro.
- Indexação automática (chunking + embedding + persistência) a cada upload.
- Busca por similaridade vetorial no PostgreSQL/pgvector (índice HNSW, distância cosseno).
- Geração de resposta com LLM local (Ollama), respondendo no mesmo idioma da pergunta.
- Citação das fontes usadas na resposta — calculada a partir do que foi realmente
  recuperado na busca, não a partir do texto gerado pelo modelo (evita citação
  "alucinada"; ver seção 8).
- Geração de diagramas de arquitetura (Mermaid.js) a partir dos componentes e fluxos
  descritos no conteúdo indexado — útil para transcrições de palestras/reuniões técnicas
  que descrevem uma arquitetura.
- **Uma única caixa de pergunta** decide sozinha se deve responder em texto ou gerar um
  diagrama, com base em palavras-chave na própria pergunta (ex.: "desenhe", "fluxo",
  "arquitetura", "gráfico") — o usuário não precisa escolher o modo (ver seção 7 e
  [ADR 0006](../docs/adr/0006-unified-ask-endpoint-with-keyword-routing.md)).
- API REST documentada com OpenAPI/Swagger em cada serviço.
- Interface web para upload e para perguntar/gerar diagramas, sem necessidade de
  `curl`/Postman.
- Logs estruturados (formato ECS), métricas Prometheus e health checks em ambos os
  serviços Java.
- Testes unitários e de integração (estes últimos com Testcontainers, subindo um
  PostgreSQL/pgvector real durante os testes).

## 4. Tecnologias utilizadas

| Camada                | Tecnologia |
|------------------------|------------|
| Linguagem / runtime    | Java 21, Spring Boot 3.5 |
| Orquestração de IA     | Spring AI 1.0 (integração com Ollama e pgvector) |
| Banco vetorial         | PostgreSQL 16 + extensão pgvector |
| Modelos de IA          | Ollama — `nomic-embed-text` (embeddings) e `llama3.1` (chat) |
| Frontend               | HTML/CSS/JS puro (sem framework, sem build), servido por nginx |
| Diagramas              | Mermaid.js (renderizado no navegador a partir de texto gerado pelo LLM) |
| Documentação de API    | springdoc-openapi / Swagger UI |
| Observabilidade        | Micrometer + Prometheus, logs estruturados (ECS), Spring Boot Actuator |
| Testes                 | JUnit 5, Mockito, Testcontainers |
| Empacotamento          | Docker, Docker Compose |
| Build                  | Maven (projeto multi-módulo, com Maven Wrapper) |

## 5. Fluxo de ingestão (upload → indexação)

1. O usuário envia um arquivo pela interface web (ou via `POST /api/v1/documents`).
2. O `ingestion-service` identifica o tipo do arquivo pela extensão e escolhe o leitor
   correto: `PagePdfDocumentReader` para PDF, `TikaDocumentReader` para DOCX/MD/TXT.
3. O texto extraído é dividido em chunks de aproximadamente 800 tokens
   (`TokenTextSplitter`), preservando metadados como nome do arquivo, ID do documento e
   índice do chunk.
4. Cada chunk é transformado em um vetor de embedding chamando o Ollama
   (`nomic-embed-text`).
5. Os chunks (texto + metadados + vetor) são gravados na tabela `vector_store` do
   PostgreSQL/pgvector.
6. A API responde com o ID do documento, o nome do arquivo, o número de páginas e o
   número de chunks gerados.

Tudo isso acontece de forma síncrona dentro da mesma requisição — por isso a indexação é
automática e imediata.

## 6. Fluxo de consulta (pergunta → resposta com citações)

1. O usuário faz uma pergunta pela interface web (ou via `POST /api/v1/chat`).
2. O `rag-service` transforma a pergunta em um vetor de embedding (mesmo modelo usado na
   ingestão, para garantir compatibilidade das distâncias).
3. Esse vetor é usado para buscar, por similaridade de cosseno, os chunks mais
   relevantes já indexados no pgvector (top-K configurável, com limiar mínimo de
   similaridade).
4. Os chunks recuperados são numerados e montados como contexto de um prompt enviado ao
   modelo de chat (`llama3.1`), com instruções explícitas para responder apenas com base
   nesse contexto e citar as fontes.
5. A resposta do modelo é retornada ao usuário junto com uma lista de citações — essa
   lista é construída diretamente a partir dos chunks recuperados na busca (arquivo,
   índice do chunk, score), e não a partir do texto gerado pelo modelo.

## 7. Roteamento único e fluxo de geração de diagramas

A interface web tem **uma única caixa de pergunta** para tudo. Ao enviar, ela chama
`POST /api/v1/ask`, e é o próprio `rag-service` quem decide o que fazer:

1. O serviço verifica se a pergunta contém palavras que indicam pedido de diagrama
   ("diagrama", "desenhe", "fluxo", "arquitetura", "imagem", "gráfico", "flowchart", etc.)
   — a checagem ignora acentos, então "gráfico" e "grafico" funcionam igual.
2. Se sim, segue o fluxo de diagrama abaixo. Se não, segue o fluxo de chat normal
   (seção 6). A resposta sempre indica qual dos dois foi usado (campo `type`).

Fluxo de diagrama, quando acionado:

1. O `rag-service` recupera os chunks mais relevantes no pgvector, exatamente como no
   fluxo de chat.
2. Em vez de gerar uma resposta em prosa, o modelo é instruído a responder **apenas** com
   uma definição de diagrama em [Mermaid.js](https://mermaid.js.org/) (formato
   `flowchart LR`/`flowchart TD`), representando somente os componentes e conexões
   explicitamente mencionados no contexto.
3. O backend faz uma limpeza defensiva no texto retornado: remove blocos de código
   Markdown (` ```mermaid ` ) que o modelo às vezes inclui por conta própria; força todo
   rótulo de nó a vir entre aspas — um rótulo sem aspas contendo parênteses ou vírgula
   (ex.: `B[Multi-AZ (alta disponibilidade)]`) quebra a sintaxe do Mermaid, mas com aspas
   (`B["Multi-AZ (alta disponibilidade)"]`) funciona sempre; e corrige um `>` sobrando que
   o modelo às vezes coloca depois do rótulo de uma seta (`-->|Backup|>` vira
   `-->|Backup|`, a única forma válida).
4. Se o contexto recuperado não descrever nenhuma arquitetura, processo ou fluxo, o
   modelo retorna um único nó "dados insuficientes" em vez de inventar um diagrama.
5. A definição Mermaid é enviada ao navegador, que a renderiza como SVG diretamente com
   `mermaid.render(...)` — nenhum cálculo de posição de caixa/seta acontece no backend.

O roteamento por palavra-chave é uma checagem simples de texto, não uma chamada extra ao
LLM — ver [ADR 0006](../docs/adr/0006-unified-ask-endpoint-with-keyword-routing.md).
Os endpoints originais (`/api/v1/chat` e `/api/v1/diagrams`) continuam existindo
separadamente para quem já sabe qual dos dois quer usar.

A qualidade do diagrama (o quanto os componentes aparecem de fato conectados em um fluxo
coerente, em vez de pares soltos) depende da capacidade do modelo local (`llama3.1`) de
seguir as instruções — ver [ADR 0005](../docs/adr/0005-mermaid-for-generated-diagrams.md).

## 8. Decisões de arquitetura (resumo)

Documentadas em detalhe em [`../docs/adr`](../docs/adr):

- **ADR 0001** — PostgreSQL + pgvector como banco vetorial, em vez de uma solução
  dedicada (Pinecone, Weaviate, Milvus), pela simplicidade operacional de manter um único
  banco de dados.
- **ADR 0002** — `ingestion-service` e `rag-service` compartilham o mesmo banco/tabela
  como simplificação deliberada do MVP; `ingestion-service` é o único que cria o schema
  (`initialize-schema: true`), `rag-service` só lê.
- **ADR 0003** — Ollama para inferência local, permitindo rodar o projeto inteiro com um
  único comando, sem chave de API nem custo por chamada.
- **ADR 0004** — As citações retornadas vêm sempre da busca vetorial real, nunca de um
  parsing do texto do modelo, evitando o problema comum de "citação alucinada" em RAG.
- **ADR 0005** — Diagramas de arquitetura são gerados pelo LLM como texto Mermaid.js
  (renderizado no navegador), em vez de o backend calcular layout/posições; inclui as
  correções defensivas de sempre citar rótulos de nó e corrigir setas malformadas.
- **ADR 0006** — Existe um único endpoint `/api/v1/ask` que decide, por palavra-chave na
  pergunta, se deve responder em texto ou gerar um diagrama — a interface web usa só ele.

## 9. Como executar

Pré-requisito: Docker e Docker Compose instalados. Não é necessário Java, Maven nem
Ollama instalados na máquina — tudo roda dentro dos containers.

```bash
cd enterprise-rag-platform
docker compose up -d --build
```

Na primeira execução, o Ollama baixa os modelos (`nomic-embed-text` e `llama3.1`,
alguns GB) automaticamente — isso pode levar alguns minutos. Depois disso, o cache fica
salvo em um volume Docker e as próximas subidas são rápidas.

| Interface           | Endereço                                   |
|----------------------|---------------------------------------------|
| Interface web        | http://localhost:3000                        |
| Swagger — ingestion   | http://localhost:8081/swagger-ui.html        |
| Swagger — rag         | http://localhost:8082/swagger-ui.html        |

Para derrubar tudo: `docker compose down` (os dados e modelos ficam preservados em
volumes; para apagar tudo também, `docker compose down -v`).

## 10. Endpoints principais da API

### Upload de documento

```
POST /api/v1/documents
Content-Type: multipart/form-data
Campo: file
```

Resposta (`201 Created`):

```json
{ "documentId": "…", "source": "arquivo.md", "pageCount": 1, "chunkCount": 3 }
```

### Perguntar qualquer coisa (endpoint usado pela interface web)

```
POST /api/v1/ask
Content-Type: application/json
```

```json
{ "question": "Como funciona o padrão SAGA?" }
```

Resposta (`200 OK`) — decide sozinho entre texto e diagrama:

```json
{
  "type": "answer",
  "answer": "O padrão SAGA coordena transações distribuídas... [1]",
  "mermaid": null,
  "citations": [
    { "source": "arquivo.md", "chunkIndex": 0, "score": 0.83, "snippet": "O padrão SAGA..." }
  ]
}
```

Se a pergunta pedir um diagrama (ex.: "desenhe..."), `type` vem `"diagram"` e o campo
`mermaid` vem preenchido em vez de `answer`.

### Pergunta (endpoint específico, só texto)

```
POST /api/v1/chat
Content-Type: application/json
```

```json
{ "question": "Como funciona o padrão SAGA?" }
```

Resposta (`200 OK`):

```json
{
  "answer": "O padrão SAGA coordena transações distribuídas... [1]",
  "citations": [
    {
      "source": "arquivo.md",
      "chunkIndex": 0,
      "score": 0.83,
      "snippet": "O padrão SAGA..."
    }
  ]
}
```

### Diagrama de arquitetura (endpoint específico, só diagrama)

```
POST /api/v1/diagrams
Content-Type: application/json
```

```json
{ "question": "Desenhe a arquitetura de recuperação de desastres descrita" }
```

Resposta (`200 OK`):

```json
{
  "mermaid": "flowchart LR\n    A[\"Ambiente de Produção\"] --> B[\"Ambiente de Recuperação\"]\n    ...",
  "citations": [
    { "source": "palestra-aws.txt", "chunkIndex": 3, "score": 0.74, "snippet": "..." }
  ]
}
```

## 11. Estrutura de pastas do repositório

```
enterprise-rag-platform/
├── ingestion-service/   # upload, parsing, chunking, embedding, persistência
├── rag-service/          # busca, geração de resposta, citações
├── web-ui/               # interface web (HTML/CSS/JS + nginx)
├── postgres-pgvector/    # script de inicialização do banco (extensão vector)
├── docs/                 # documentação técnica em inglês (arquitetura + ADRs)
├── documento/            # esta documentação (visão consolidada em português)
├── docker-compose.yml    # orquestração de todos os serviços
└── pom.xml               # projeto Maven multi-módulo (parent)
```

## 12. Testes

```bash
./mvnw test      # testes unitários — não precisam de Docker
./mvnw verify     # inclui testes de integração com Testcontainers — precisa de Docker
```

Os testes de integração sobem um PostgreSQL/pgvector real via Testcontainers e mockam
apenas os modelos de IA (embedding e chat), validando o fluxo completo de
ingestão/consulta contra um banco de verdade, e não contra um dublê genérico.

## 13. Roadmap (próximos passos)

- **chat-service**: conversas com memória de múltiplos turnos (Spring AI `ChatMemory`).
- **auth-service**: autenticação JWT/OAuth2, permitindo ingestão e chat por
  usuário/tenant.
- **Busca híbrida + re-ranking**: combinar busca vetorial com busca textual
  (`tsvector` do PostgreSQL) e reordenar os candidatos com um cross-encoder.
- **Manifests Kubernetes**: Deployments, Services, ConfigMaps e HPA para cada serviço.
- **CI**: pipeline no GitHub Actions rodando `./mvnw verify` (incluindo Testcontainers)
  a cada PR.
- **Dashboards Grafana** sobre as métricas Prometheus já expostas pelos dois serviços.
- **Verificação de groundedness**: checar automaticamente se cada afirmação da resposta
  está de fato apoiada nos chunks recuperados.
