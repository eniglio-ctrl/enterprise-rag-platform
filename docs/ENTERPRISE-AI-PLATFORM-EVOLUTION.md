# Enterprise AI Platform — visão de evolução

> Registro de produto/arquitetura em 2026-08-04. Isto **não é** autorização
> para iniciar os projetos abaixo nem um cronograma fechado. A única etapa a
> ser executada agora é consolidar o `enterprise-rag-platform`; quando ela
> terminar, as próximas etapas serão planejadas a partir de evidências reais
> (uso, carga, custos e lacunas encontradas), não implementadas por antecipação.

## Visão

O `enterprise-rag-platform` é o primeiro produto de uma futura Enterprise AI
Platform. Cada produto deve resolver um problema claro e poder ser executado,
implantado e evoluído independentemente. A plataforma não deve ser uma cadeia
linear de serviços nem compartilhar bancos de dados entre produtos.

```text
Shared contracts: identity · audit · events · observability · tenant context

Enterprise RAG       AI Gateway       AI Security       Agent Platform
knowledge retrieval  AI governance    security analysis  task orchestration
```

Os contratos compartilhados só devem virar um módulo/biblioteca quando forem
usados por pelo menos dois produtos. Candidatos iniciais: contexto de tenant,
formato de eventos de auditoria, correlation ID, telemetria e interfaces para
modelos de IA. Cada produto mantém seus próprios dados e integrações ocorrem
por APIs ou eventos versionados.

## Ordem deliberada

1. **Enterprise RAG Platform — agora.** Fechar a sua prontidão operacional:
   testes reproduzíveis, segredos, ingestão assíncrona + storage, resiliência,
   autorização por recurso, tracing e recuperação. O detalhe permanece em
   [PRODUCTION-READINESS-ROADMAP.md](PRODUCTION-READINESS-ROADMAP.md).
2. **AI Gateway — planejar depois da etapa 1.** Deve existir antes da Agent
   Platform: é a fronteira para autenticação/tenant, quotas, rate limit,
   timeout, custo, seleção/fallback de provedor e auditoria.
3. **AI Security Platform — planejar após o gateway.** Produto de análise de
   eventos, incidentes e CVEs; consome evidências do gateway e pode fornecer
   sinais de risco e políticas, mas não substitui as proteções básicas do
   próprio gateway.
4. **Agent Platform — por último.** Orquestra agentes especializados (busca,
   segurança, SQL e relatórios) já atrás das políticas do AI Gateway. Ações
   externas ou de alto impacto exigem aprovação humana.

## Segurança na borda

O AI Gateway deve filtrar a solicitação antes de qualquer agente ou provedor
externo. O escopo inicial a planejar inclui:

- autenticação, autorização e contexto de tenant;
- limites de tamanho, taxa, concorrência e custo;
- política de modelos e fornecedores permitidos;
- timeout, retry, circuit breaker e fallback explícito;
- mascaramento/detecção de dados sensíveis;
- sinais de prompt injection ou jailbreak;
- auditoria, métricas e tracing de cada decisão.

O desenho de referência é:

```text
Client -> API Gateway -> AI Gateway -> Agent Platform -> specialist services/providers
                                  |                    
                                  +-> audit/risk signals -> AI Security Platform
```

## Critério para iniciar a próxima etapa

Ao concluir a etapa 1, realizar uma nova sessão de planejamento. Ela deve
escolher um caso de uso real para o AI Gateway, delimitar o primeiro MVP e
registrar os trade-offs em um ADR. Nenhum componente futuro deve ser criado
apenas para completar um diagrama ou adicionar tecnologia ao currículo.
