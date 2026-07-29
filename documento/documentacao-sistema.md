# Documentação — enterprise-rag-platform

## 1. Visão geral

O **enterprise-rag-platform** é uma plataforma RAG (*Retrieval-Augmented Generation*)
desenhada como um sistema corporativo: microsserviços Java/Spring Boot independentes,
autenticação JWT, isolamento por tenant, banco PostgreSQL com pgvector, inferência
local-first, interface web, observabilidade e decisões arquiteturais registradas em
ADRs.

O sistema permite enviar documentos e consultá-los em linguagem natural. As respostas
trazem as fontes recuperadas; quando solicitado explicitamente, o sistema gera um
diagrama Mermaid a partir do conteúdo indexado. Também há ingestão de imagem e áudio,
anexo efêmero de imagem a uma pergunta e conversas de múltiplos turnos pela API.

## 2. Arquitetura

![Arquitetura do enterprise-rag-platform](./diagrama-arquitetura.svg)

| Componente | Papel | Porta |
|---|---|---:|
| `web-ui` | Interface estática para login, upload e perguntas | 3000 |
| `auth-service` | Registro/login, emissão de JWT RS256 e JWKS | 8084 |
| `ingestion-service` | Valida, extrai, divide, gera embeddings e indexa arquivos | 8081 |
| `rag-service` | Busca híbrida, geração de respostas/diagramas e citações | 8082 |
| `chat-service` | Conversas com memória; reutiliza a busca do `rag-service` | 8083 |
| PostgreSQL + pgvector | Vetores, metadados, usuários e conversas | 5432 |
| Ollama | Modelos locais de embedding, chat e visão | 11434 |
| Whisper ASR | Transcrição local de áudio, quando habilitado | interno |

O diagrama SVG é uma visão de alto nível. Os fluxos atualizados e detalhados estão em
[`docs/architecture.md`](../docs/architecture.md).

## 3. Funcionalidades

- Upload e indexação de PDF, DOCX, Markdown e texto puro.
- Ingestão de imagens (`png`, `jpg`, `jpeg`, `gif`, `webp`) por descrição via modelo de
  visão; a descrição, e não o binário, é indexada.
- Ingestão de áudio (`mp3`, `wav`, `m4a`, `ogg`, `flac`) por transcrição Whisper.
- Validação de extensão, tipo declarado e *magic bytes* antes de processar uploads.
- Busca híbrida: similaridade vetorial + busca textual do PostgreSQL, combinadas por
  RRF (*reciprocal rank fusion*).
- Respostas com citações derivadas dos chunks realmente recuperados.
- Re-ranking por LLM e verificação de groundedness opcionais.
- Geração de diagramas Mermaid a partir do contexto recuperado.
- Roteamento por intenção com uma chamada curta ao LLM — não por lista fixa de palavras.
- Anexo de imagem em uma pergunta, processado apenas naquela requisição e nunca indexado.
- Seleção de modelo por requisição (Ollama ou LM Studio); a opção “Automático” resolve
  hoje para o modelo padrão.
- Conversas com memória e isolamento de dados por `tenantId`/`userId`.
- Métricas Prometheus, dashboard Grafana, logs estruturados e health checks.

## 4. Segurança e isolamento

O `auth-service` emite JWTs assinados com RS256. `ingestion-service`, `rag-service` e
`chat-service` validam esses tokens pelo JWKS público do emissor. Os claims `tenantId`
e `userId` definem a identidade do chamador; a recuperação de documentos e o acesso às
conversas são restritos ao tenant correspondente.

O projeto já possui uma baseline de segurança e validação robusta de uploads. Rate
limiting, persistência da chave de assinatura, auditoria e endurecimento da demo pública
ainda são fases planejadas; o status exato está em
[`docs/SECURITY-HARDENING-ROADMAP.md`](../docs/SECURITY-HARDENING-ROADMAP.md).

## 5. Fluxos principais

### Ingestão

1. O cliente envia um arquivo autenticado a `POST /api/v1/documents`.
2. O `ingestion-service` valida extensão, `Content-Type` e conteúdo real do arquivo.
3. PDFs, DOCX, Markdown e texto são extraídos; imagens são descritas e áudios são
   transcritos.
4. O conteúdo é dividido em chunks, recebe metadados do documento e do tenant, e cada
   chunk recebe um embedding.
5. Texto, metadados e vetores são persistidos no PostgreSQL/pgvector.
6. A resposta retorna o identificador do documento, origem, páginas e chunks criados.

### Consulta e diagramas

1. O cliente chama `POST /api/v1/ask` com uma pergunta autenticada; pode anexar uma
   imagem opcionalmente.
2. O `rag-service` classifica a intenção como `RESPOSTA` ou `DIAGRAMA` com uma chamada
   LLM de temperatura zero.
3. A pergunta é transformada em embedding e pesquisada no tenant por busca vetorial e
   textual, combinadas por RRF.
4. O serviço gera resposta textual ou Mermaid conforme a intenção e retorna as citações
   dos chunks recuperados.
5. `grounded=true` pede uma segunda avaliação de suporte da resposta; `rerank=true`
   pede uma etapa adicional de ordenação por LLM.

O roteamento por LLM substituiu a antiga lista de palavras-chave após um falso positivo
real em perguntas sobre imagens. A decisão está registrada no
[ADR 0024](../docs/adr/0024-llm-based-ask-routing.md).

### Conversas de múltiplos turnos

`chat-service` cria conversas e guarda o histórico no PostgreSQL. Em cada mensagem,
ele chama o endpoint de recuperação do `rag-service` com o mesmo token do usuário,
junta os chunks relevantes à memória da conversa e gera uma resposta contextualizada.
No momento, a interface web usa diretamente o `rag-service`; as conversas são expostas
pela API própria do `chat-service`.

## 6. Como executar localmente

Pré-requisito: Docker e Docker Compose. A primeira inicialização pode demorar enquanto
os modelos do Ollama são baixados.

```bash
cd enterprise-rag-platform
docker compose up --build
```

| Interface | Endereço |
|---|---|
| Web UI | http://localhost:3000 |
| Swagger — auth | http://localhost:8084/swagger-ui.html |
| Swagger — ingestion | http://localhost:8081/swagger-ui.html |
| Swagger — RAG | http://localhost:8082/swagger-ui.html |
| Swagger — chat | http://localhost:8083/swagger-ui.html |
| Grafana | http://localhost:3001 |
| Prometheus | http://localhost:9090 |

Registre uma conta na interface ou em `POST /api/v1/auth/register`; os demais endpoints
locais exigem `Authorization: Bearer <token>`.

## 7. Endpoints principais

| Serviço | Endpoint | Finalidade |
|---|---|---|
| auth | `POST /api/v1/auth/register` | Cria conta e retorna JWT |
| auth | `POST /api/v1/auth/login` | Autentica e retorna JWT |
| ingestion | `POST /api/v1/documents` | Envia e indexa um documento |
| RAG | `POST /api/v1/ask` | Decide entre resposta e diagrama |
| RAG | `POST /api/v1/chat` | Gera somente resposta textual |
| RAG | `POST /api/v1/diagrams` | Gera somente diagrama Mermaid |
| RAG | `GET /api/v1/models` | Lista modelos de chat disponíveis |
| chat | `POST /api/v1/conversations` | Cria conversa |
| chat | `POST /api/v1/conversations/{id}/messages` | Envia mensagem |
| chat | `GET /api/v1/conversations/{id}/messages` | Lista mensagens |

Exemplo de pergunta:

```json
{
  "question": "Como funciona o padrão SAGA?",
  "grounded": true,
  "rerank": false,
  "model": "auto"
}
```

O retorno de `/api/v1/ask` tem `type: "answer"` ou `type: "diagram"`, citações e,
quando solicitado, o veredito de groundedness. Consulte o Swagger de cada serviço para
o contrato completo e o [README](../README.md) para exemplos `curl` atualizados.

## 8. Estrutura do repositório

```text
enterprise-rag-platform/
├── platform-common/       # código compartilhado entre os serviços
├── auth-service/          # JWT RS256 e JWKS
├── ingestion-service/     # upload e indexação
├── rag-service/           # recuperação, geração e citações
├── chat-service/          # memória de conversa
├── web-ui/                # HTML/CSS/JS servido por nginx
├── postgres-pgvector/     # inicialização do banco
├── observability/         # Prometheus e Grafana
├── kubernetes/            # manifests Kustomize para kind
├── docs/                  # arquitetura, ADRs e roadmaps
├── documento/             # esta visão consolidada em português
├── docker-compose.yml
└── pom.xml
```

## 9. Testes e operação

```bash
./mvnw test       # testes unitários
./mvnw verify     # inclui integração com Testcontainers; requer Docker
```

Os testes de integração usam PostgreSQL real em containers e simulam somente os modelos
de IA. Há ainda um benchmark RAG opcional, com modelos locais reais, documentado no
[README](../README.md#rag-quality-benchmark).

Para Kubernetes local, veja [`kubernetes/README.md`](../kubernetes/README.md). Os
manifests ainda não incluem `auth-service`; essa é uma limitação conhecida e registrada
no [roadmap principal](../docs/ROADMAP.md).

## 10. Referências e próximos passos

- [README](../README.md): guia principal de uso e operação.
- [`docs/architecture.md`](../docs/architecture.md): fluxos e diagramas detalhados.
- [`docs/adr/`](../docs/adr): decisões arquiteturais, incluindo decisões substituídas.
- [`docs/ROADMAP.md`](../docs/ROADMAP.md): prioridade única das pendências.
- [`docs/DEMO-DEPLOYMENT.md`](../docs/DEMO-DEPLOYMENT.md): configuração e limites da demo pública.

As iniciativas em aberto se concentram em completar o Kubernetes com autenticação,
endurecer segurança e decidir conscientemente os próximos investimentos em orquestração
multi-LLM. Não há um roadmap antigo de funcionalidades básicas pendentes: autenticação,
chat com memória, busca híbrida, CI, observabilidade, benchmark e deploy público já
foram implementados.
