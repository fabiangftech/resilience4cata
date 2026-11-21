# Compatibilidade

O que uma atualização dentro de uma versão major mantém funcionando, a partir da 1.0.0, e o que pode crescer em uma versão minor.

O resilience4cata segue o [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Até a 1.0.0 uma versão minor pode mudar a API, e o [changelog](../changelog.md) lista cada mudança incompatível. A partir da 1.0.0, as versões minor e patch da mesma major mantêm compatível tudo o que está nesta página; só uma nova versão major pode quebrá-lo.

## A API Java :id=java-api

A API é todo membro public e protected dos pacotes que um módulo publicado exporta (`dev.resilience4cata`, `dev.resilience4cata.event`, `dev.resilience4cata.config`, `dev.resilience4cata.testkit` e assim por diante; para o Spring Boot, `dev.resilience4cata.spring`; para o Quarkus, `dev.resilience4cata.quarkus`; para o Micronaut, cujo jar não tem `module-info`, os tipos públicos de `dev.resilience4cata.micronaut`). Dentro de uma versão major ela continua compatível em binário e em código-fonte. O build verifica isso a cada mudança: compara cada módulo com a última versão publicada e, a partir da 1.0.0, falha diante de uma mudança incompatível.

Não fazem parte da API:

- Membros marcados com `@Internal`: são públicos só para que outro módulo do resilience4cata, ou o `ServiceLoader`, possa alcançá-los.
- Membros marcados com `@Experimental`: podem mudar em qualquer versão minor até se estabilizarem. Hoje nada está marcado com `@Experimental`.
- Pacotes que um módulo não exporta, como `dev.resilience4cata.internal`, e pacotes que são exportados ou públicos só por causa de um framework: `dev.resilience4cata.spring.autoconfigure` do Spring Boot (exportado para que `exclude` possa nomeá-lo) e `dev.resilience4cata.quarkus.runtime` do Quarkus (os beans e as raízes de configuração da extensão). Para a auto-configuração, o que se mantém são os nomes das classes (para `exclude`), os nomes dos beans e os nomes das propriedades `resilience4cata.spring.*`.
- `dev.resilience4cata.generated` e `dev.resilience4cata.reactor.generated`: são exportados só para o código gerado. Não os chame a partir do seu próprio código.

## Código gerado :id=generated-code

O código gerado pelo processor `1.a` roda no core (e no reactor) `1.b` para todo `b >= a`. Uma biblioteca compilada com o resilience4cata continua funcionando, portanto, quando a aplicação que a usa atualiza o resilience4cata dentro da mesma versão major. A outra direção não é suportada: em um runtime mais antigo o construtor gerado falha na hora com [R4C-RT-021](../errors.md#r4c-rt-021). Importar o BOM mantém as versões alinhadas. O texto do código-fonte gerado em si não faz parte do contrato e pode mudar em qualquer versão.

## Além do Java :id=beyond-java

Estes também se mantêm dentro de uma versão major:

- As anotações e seus atributos, e as [chaves de configuração](configuration.md) com suas formas de variável de ambiente.
- Os nomes de propriedades das integrações: `resilience4cata.spring.*`, `resilience4cata.micronaut.*` e `quarkus.resilience4cata.*`.
- Nomes e tags de métricas, os mesmos nos módulos Micrometer e OpenTelemetry ([Nomes de métricas](../guides/observability.md#metric-names)). Os nomes continuam com pontos, todo meter mantém sua tag `name`, e as taxas do circuit breaker continuam em porcentagens.
- O JSON dos endpoints do Spring Boot e do Micronaut, que é `ResilienceSnapshot.toMap()` ([Snapshots](../guides/observability.md#snapshots)).
- Códigos de erro como `R4C-ATR-001`. Um código nunca é renumerado nem reutilizado, e um código aposentado mantém sua seção na [referência de erros](../errors.md). O texto de uma mensagem pode melhorar em qualquer versão, então compare pelo código, não pelo texto.

## Como é verificado :id=how-it-is-checked

O CI executa estas verificações a cada mudança no resilience4cata:

- **A API Java**: cada módulo publicado é comparado com a última versão publicada pelo japicmp.
- **Código gerado**: os testes de ponta a ponta são compilados com o processor, as anotações e os módulos da última versão a partir do Maven Central, e executados sobre os módulos novos.
- **Arquivos de configuração**: um arquivo escrito para a última versão, que usa todas as chaves que essa versão conhece (todos os atributos de todas as políticas, configs nomeadas com `extends`, instâncias, um perfil), é lido por essa versão a partir do Maven Central e pelo código novo. O código novo não pode reportar nenhum problema nem registrar nenhum warning, e precisa resolver cada configuração para os mesmos valores.
- **Todo o resto desta página**: nomes, tipos, unidades e tags de métricas nos dois módulos de métricas, o JSON dos endpoints, as chaves de configuração com suas variáveis de ambiente e valores, os nomes de propriedades das integrações (e os nomes de auto-configuração e de beans do Spring Boot) e os códigos de erro são comparados com os arquivos do diretório [`contract`](https://github.com/fabiangftech/resilience4cata/tree/main/contract) do repositório. Uma entrada pode ser adicionada ali em uma versão minor; remover ou mudar uma quebra o build.

## O que pode crescer em uma versão minor :id=growth

Adicionar é compatível, então uma versão minor pode fazer qualquer uma das coisas a seguir. Escreva código que tolere isso:

- **Constantes de enum** podem ser adicionadas (`CircuitBreakerState`, `RetryEvent.Reason`, `Classifier.Outcome` e outras). Mantenha um ramo `default` em um `switch` sobre elas.
- **Tipos de evento** podem ser adicionados, e records de eventos existentes podem ganhar novos componentes. Mantenha um ramo `default` em um `switch` sobre eventos, e leia os eventos pelos seus acessores, não com record patterns que os desconstroem. Um record que ganha um componente mantém seu construtor anterior, então o código que cria eventos, como os testes de um listener, continua compilando.
- **Tipos sealed** podem ganhar novos subtipos quando uma política é adicionada: `PolicyConfig` uma nova configuração, `CallRejectedException` uma nova rejeição, `ResilienceEvent` uma nova interface de evento. Mantenha um ramo `default` em um `switch` sobre eles.
- **Métodos** podem ser adicionados aos handles (`CircuitBreakerHandle` e os outros) e a `FallbackContext`. A biblioteca implementa essas interfaces; não as implemente nem faça mock delas.
- **Métodos de `Ticker`** adicionados no futuro serão métodos `default`, então sua própria implementação continua compilando.
- **Atributos de anotação** podem ser adicionados, sempre com um valor padrão.
- **Snapshots** podem ganhar novos componentes e o JSON dos endpoints novas chaves. Como com os eventos, um record de snapshot mantém seu construtor anterior. Ignore as chaves que você não conhece.
- **Métricas**, códigos de erro e chaves de configuração podem ser adicionados.
