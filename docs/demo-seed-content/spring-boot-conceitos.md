# Spring Boot: conceitos fundamentais

## O problema que o Spring Boot resolve

O Spring Framework "clássico" (pré-2014) exigia bastante configuração XML
ou Java explícita para ligar os beans da aplicação — um projeto novo
começava com boilerplate considerável antes de escrever qualquer lógica de
negócio. O Spring Boot inverteu essa lógica: em vez de você configurar tudo
manualmente, ele assume convenções sensatas por padrão e só exige
configuração explícita quando você quer se desviar delas ("convention over
configuration"). Um projeto novo com `spring-boot-starter-web` já sobe um
servidor HTTP embutido (Tomcat, por padrão) funcionando, sem nenhum
`web.xml` ou configuração de servlet container.

## Auto-configuração

O mecanismo central é a auto-configuração condicional: o Spring Boot
examina o classpath da aplicação e ativa beans automaticamente com base no
que encontra. Se o `spring-boot-starter-data-jpa` está no classpath e há
uma `DataSource` configurada, ele automaticamente cria um
`EntityManagerFactory`, um `TransactionManager`, etc. — sem que o
desenvolvedor precise declarar esses beans manualmente. Isso é implementado
via anotações como `@ConditionalOnClass`, `@ConditionalOnMissingBean`,
`@ConditionalOnProperty`: cada auto-configuração só se ativa se determinada
classe estiver presente, se nenhum outro bean do mesmo tipo já tiver sido
declarado, ou se uma propriedade específica estiver definida.

Esse mesmo mecanismo é o que permite dois provedores de IA coexistirem no
mesmo classpath (por exemplo, Ollama e um provedor compatível com a API da
OpenAI) sem conflito — desde que cada bean seja qualificado explicitamente
por nome (`@Qualifier`) em vez de depender da resolução automática por
tipo, que só funciona bem quando existe um único candidato inequívoco.

## Starters

Um "starter" (`spring-boot-starter-*`) é um artefato Maven/Gradle que não
contém código de verdade — é só um agregador de dependências transitivas
compatíveis entre si. `spring-boot-starter-web` traz Spring MVC, Jackson
(serialização JSON) e um servidor embutido, todos em versões já testadas
juntas. Isso resolve o problema clássico de "dependency hell", onde
combinar versões de bibliotecas diferentes manualmente podia gerar
conflitos sutis de classpath.

## Configuração via `application.yml`/`application.properties`

Propriedades externas seguem uma ordem de precedência bem definida
(argumentos de linha de comando > variáveis de ambiente > arquivo de
propriedades > valores default no código). Perfis (`spring.profiles.active`)
permitem ter configurações completamente diferentes para desenvolvimento
local, testes automatizados e produção, sem duplicar código — só o arquivo
`application-{perfil}.yml` muda. Um detalhe frequentemente mal compreendido:
quando uma lista (como `spring.autoconfigure.exclude`) é redefinida num
perfil específico, ela **substitui** a lista base inteira, não faz merge
item a item — um erro comum é assumir que só o item alterado precisa ser
repetido.

## Actuator e observabilidade

O módulo Actuator expõe endpoints de gerenciamento (`/actuator/health`,
`/actuator/metrics`, `/actuator/prometheus`) prontos para uso, sem precisar
implementar verificação de saúde manualmente. Health indicators customizados
podem ser registrados para verificar dependências específicas (um banco de
dados, uma fila, um serviço externo) — o status agregado reflete o pior
indicador individual, então uma dependência crítica fora do ar já derruba o
health check geral, permitindo que um orquestrador (Kubernetes, por
exemplo) saiba que não deve rotear tráfego para essa instância.

## Testes

`@SpringBootTest` sobe um contexto de aplicação real (ou parcial, com
`@WebMvcTest`/`@DataJpaTest` para testes mais focados e rápidos), permitindo
testar a integração real entre componentes sem mockar tudo. Testcontainers
complementa isso subindo dependências reais (um Postgres real, por exemplo)
em containers Docker efêmeros durante o teste, evitando a divergência entre
"funciona com mock" e "funciona com o banco de dados real" — uma classe
inteira de bugs de integração que só aparecem em produção fica visível já
no CI.
