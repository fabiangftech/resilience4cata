# Instalação

Duas linhas bastam para começar: `resilience4cata-all` e o processador de anotações. Esta página cobre essa configuração, os módulos individuais, Gradle e Maven, IDEs, JPMS e outros processadores de anotações.

**Requisitos:** Java 21 ou mais recente, tanto para compilar quanto para executar (os jars são construídos com `--release 21` e testados em Java 21 e 25). Coordenadas: groupId `dev.resilience4cata`, versão `1.1.0`, no Maven Central (estável desde a 1.0.0: dentro da 1.x ela continua [compatível](../reference/compatibility.md); a última versão 0.x é a 0.13.0).

## Os artefatos :id=the-artifacts

| Artefato | O que é | Escopo |
|---|---|---|
| `resilience4cata-bom` | Alinha as versões de todos os artefatos abaixo | `platform(...)` (Gradle), `import` (Maven) |
| `resilience4cata-all` | Desde a 0.8.0. Uma dependência para `core`, `annotations` e `config`, sem código próprio ([o jeito curto](#the-short-way)) | `implementation` (Gradle), `compile` padrão (Maven) |
| `resilience4cata-core` | Políticas, `ResilienceRegistry`, `Resilient`, `Pipeline`, eventos, snapshots. Sua única dependência é JSpecify (`org.jspecify:jspecify`) | `implementation` (Gradle), `compile` padrão (Maven) |
| `resilience4cata-annotations` | `@Retry`, `@Hedge`, `@CircuitBreaker`, `@RateLimiter`, `@AdaptiveLimit`, `@Timeout`, `@Bulkhead`, `@Fallback`, `@ResiliencePolicy`, `@PolicyOrder`. Retenção `CLASS`, então não é necessário em tempo de execução | `compileOnly` (Gradle), `provided` (Maven) |
| `resilience4cata-processor` | O processador de anotações que gera o código. **Obrigatório** quando você usa as anotações: não há alternativa com reflection | `annotationProcessor` (Gradle), `annotationProcessorPaths` (Maven). Nunca no class path de execução |
| `resilience4cata-config` | Opcional. Configuração externa: arquivos de propriedades, variáveis de ambiente, propriedades de sistema ([guia de configuração](../guides/configuration.md)) | `implementation` / escopo padrão |
| `resilience4cata-micrometer` | Opcional, desde a 0.3.0. Métricas Micrometer: contadores a partir de eventos, gauges para breakers, bulkheads e rate limiters ([guia do Micrometer](../guides/micrometer.md)). Traz `micrometer-core` | `implementation` / escopo padrão |
| `resilience4cata-reactor` | Opcional, desde a 0.11.0. Project Reactor: métodos anotados que devolvem `Mono` ou `Flux`, e `MonoPipelineOperator`/`FluxPipelineOperator` ([guia do Project Reactor](../guides/reactor.md)). Traz `reactor-core` (3.6 ou mais recente) | `implementation` / escopo padrão |
| `resilience4cata-spring` | Opcional, desde a 0.4.0. Auto-configuração para Spring Boot 4.1+: o registry a partir de `application.yml`, beans anotados protegidos, health indicator, endpoint do actuator, métricas ([guia do Spring Boot](../guides/spring.md)). Traz `core` e `config` | `implementation` / escopo padrão |
| `resilience4cata-micronaut` | Opcional, desde a 0.5.0. Integração com Micronaut 5.2+ (precisa de Java 25, o único módulo acima de Java 21): o registry a partir de `application.yml`, beans de interface `@ResilientBean` e classes anotadas protegidos, health indicator, endpoint de gerenciamento, métricas ([guia do Micronaut](../guides/micronaut.md)). Traz `core` e `config` | `implementation` / escopo padrão |
| `resilience4cata-quarkus` | Opcional, desde a 0.5.0. Extensão para Quarkus 3.40+: o registry a partir de `application.properties`, beans anotados e REST clients protegidos, readiness check, métricas, verificações em tempo de build ([guia do Quarkus](../guides/quarkus.md)). Traz `core` e `config`; o Quarkus adiciona `resilience4cata-quarkus-deployment` em tempo de build | `implementation` / escopo padrão |
| `resilience4cata-testkit` | Opcional. `ManualClock` e `ResilienceTesting` para testes determinísticos ([guia de testes](../guides/testing.md)). Não depende de JUnit | `testImplementation` / `test` |
| `resilience4cata-junit` | Opcional, desde a 0.3.0. Extensão JUnit Jupiter (`@ResilienceTest`): um `ManualClock` novo e um registry sobre ele para cada teste, como parâmetros ([guia de testes](../guides/testing.md#junit)). Traz o testkit; precisa de JUnit 5.10 ou mais recente, JUnit 6 incluído | `testImplementation` / `test` |

`core` precisa ser pelo menos tão novo quanto `processor` (mesma versão major); o BOM os mantém em uma única versão.

## O jeito curto :id=the-short-way

`resilience4cata-all` depende de `core`, `annotations` e `config`, então uma única dependência dá a você as políticas, as anotações e a [configuração externa](../guides/configuration.md). O processador ainda precisa de sua própria linha: as ferramentas de build só executam processadores de anotações a partir do processor path, então nenhuma dependência pode trazê-lo.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.1.0'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.1.0'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.1.0")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.1.0")
}
```

#### **Maven**

```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>1.1.0</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.1.0</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

Com Maven, `annotationProcessorPaths` substitui a descoberta de qualquer outro processador: se você usa Lombok, MapStruct ou outro, liste-o ali também ([Com outros processadores de anotações](#with-other-annotation-processors)).

Escreva a mesma versão nas duas linhas: `core` precisa ser pelo menos tão novo quanto `processor`, e mantê-los iguais é o mais simples. As anotações acabam também no class path de execução; são um jar pequeno que nada lê em tempo de execução. Para mantê-las só na compilação, ou para deixar `config` de fora, adicione os módulos um a um como abaixo.

## Módulo por módulo :id=module-by-module

Importe primeiro o BOM, depois adicione os artefatos sem versões.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')   // processor path is resolved separately

    implementation 'dev.resilience4cata:resilience4cata-core'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-config'          // optional
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'     // optional
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-core")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-config")          // optional
    testImplementation("dev.resilience4cata:resilience4cata-testkit")     // optional
}
```

#### **Maven**

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.resilience4cata</groupId>
      <artifactId>resilience4cata-bom</artifactId>
      <version>1.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-core</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-annotations</artifactId>
    <scope>provided</scope>
  </dependency>
  <!-- optional -->
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-config</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-testkit</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.13.0</version>
      <configuration>
        <release>21</release>
        <annotationProcessorPaths>
          <path>
            <groupId>dev.resilience4cata</groupId>
            <artifactId>resilience4cata-processor</artifactId>
            <version>1.1.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

<!-- tabs:end -->

Por que cada parte está ali:

- **Gradle: o BOM também em `annotationProcessor`.** O Gradle resolve o processor path separadamente do class path de compilação, então uma platform adicionada a `implementation` não dá a versão ao processador.
- **Maven: a `<version>` explícita em `<path>`.** Versões do `maven-compiler-plugin` anteriores à 3.12.0 não consultam `<dependencyManagement>` para os processor paths e falham com "version can neither be null, empty nor blank". A partir da 3.12.0, a versão pode vir do BOM importado; escrevê-la funciona com qualquer versão do plugin. Mantenha-a igual à do BOM.
- **O processador vai no processor path, não no class path.** Desde o JDK 23, o `javac` não executa mais processadores que encontra no class path, a menos que seja pedido, e o processador não tem nada que fazer no seu artefato de execução.

O processador está registrado no Gradle como processador incremental *isolating*, então editar um arquivo recompila só o que depende dele.

### Tipos anotados nos fontes de teste :id=annotated-types-in-test-sources

Se você anota interfaces ou classes em `src/test` (dublês de teste, clientes só de teste), o source set de teste também precisa das anotações e do processador.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    testCompileOnly 'dev.resilience4cata:resilience4cata-annotations'
    testAnnotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    testAnnotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    testCompileOnly("dev.resilience4cata:resilience4cata-annotations")
    testAnnotationProcessor(platform("dev.resilience4cata:resilience4cata-bom:1.1.0"))
    testAnnotationProcessor("dev.resilience4cata:resilience4cata-processor")
}
```

#### **Maven**

Nada a adicionar: a configuração do `maven-compiler-plugin` também se aplica a `testCompile`, e as dependências `provided` estão no class path de teste.

<!-- tabs:end -->

## Spring Boot

Para uma aplicação Spring Boot 4.1+, dependa de `resilience4cata-spring` em vez de `core` (ele traz `core` e `config`); as anotações e o processador ficam como acima. O [guia do Spring Boot](../guides/spring.md) cobre o resto.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')

    implementation 'dev.resilience4cata:resilience4cata-spring'           // brings core and config
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-spring")           // brings core and config
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-spring</artifactId>
</dependency>
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-annotations</artifactId>
  <scope>provided</scope>
</dependency>
<!-- and resilience4cata-processor in annotationProcessorPaths, as above -->
```

<!-- tabs:end -->

## Micronaut

Para uma aplicação Micronaut 5.2+ (Java 25), dependa de `resilience4cata-micronaut` em vez de `core` (ele traz
`core` e `config`); as anotações e o processador ficam como acima, ao lado do processador do próprio Micronaut, em
qualquer ordem. A opção de compilador `-Ar4c.inject=micronaut` permite que o Micronaut crie as subclasses geradas das
classes anotadas. O [guia do Micronaut](../guides/micronaut.md) cobre o resto.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')

    implementation 'dev.resilience4cata:resilience4cata-micronaut'       // brings core and config
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.add('-Ar4c.inject=micronaut')
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-micronaut")       // brings core and config
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Ar4c.inject=micronaut")
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-micronaut</artifactId>
</dependency>
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-annotations</artifactId>
  <scope>provided</scope>
</dependency>
<!-- and resilience4cata-processor in annotationProcessorPaths, after micronaut-inject-java,
     with <compilerArgs><arg>-Ar4c.inject=micronaut</arg></compilerArgs> -->
```

<!-- tabs:end -->

## Quarkus

Para uma aplicação Quarkus 3.40+, dependa de `resilience4cata-quarkus` em vez de `core` (ele traz `core` e `config`); as anotações e o processador ficam como acima. O [guia do Quarkus](../guides/quarkus.md#install) tem os trechos de build e cobre o resto.

## Verifique que funciona :id=verify-that-it-works

Anote um método de uma interface (o [início rápido](quickstart.md) tem um) e compile. Ao lado da interface, o processador escreve `<Interface>$Resilient.java`:

| Build | Fontes principais | Fontes de teste |
|---|---|---|
| Gradle | `build/generated/sources/annotationProcessor/java/main/<package>/` | `build/generated/sources/annotationProcessor/java/test/<package>/` |
| Maven | `target/generated-sources/annotations/<package>/` | `target/generated-test-sources/test-annotations/<package>/` |

Uma interface aninhada recebe seu nome binário: `Repository.Cache` vira `Repository$Cache$Resilient`.

Se o processador não rodou, o primeiro `Resilient.of(...)` falha na hora, antes de qualquer chamada, com:

```text
[R4C-RT-003] No generated class com.acme.shop.clients.PriceClient$Resilient found. Make sure that com.acme.shop.clients.PriceClient has at least one resilience annotation, and that the annotation processor runs when it is compiled. With Gradle:
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
With Maven, in the maven-compiler-plugin's <annotationProcessorPaths>:
    <path><groupId>dev.resilience4cata</groupId><artifactId>resilience4cata-processor</artifactId><version>...</version></path>
```

No Spring Boot, Micronaut e Quarkus o mesmo erro é reportado na inicialização, antes de qualquer chamada: [R4C-SPR-006](../errors.md#r4c-spr-006) no Spring Boot, [R4C-MN-007](../errors.md#r4c-mn-007) para uma classe anotada no Micronaut, e [R4C-QK-008](../errors.md#r4c-qk-008) no Quarkus, onde ele quebra o build.

## IDEs

O processador é processamento de anotações comum do `javac`, então uma IDE gera as classes desde que o processamento de anotações esteja ativado no projeto.

**IntelliJ IDEA.** Projetos Gradle são construídos com Gradle por padrão (*Settings → Build, Execution, Deployment → Build Tools → Gradle → Build and run using: Gradle*), então as classes geradas vêm do Gradle e nada mais é necessário. Se você mudar essa opção para *IntelliJ IDEA*, ou para projetos Maven, marque *Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable annotation processing*, recarregue o projeto Maven ou Gradle para que o processor path seja importado e reconstrua.

**Eclipse.** Para projetos Maven, o m2e configura o processamento de anotações através do m2e-apt: ative *Preferences → Maven → Annotation Processing → Automatically configure JDT APT*, ou defina a propriedade `<m2e.apt.activation>jdt_apt</m2e.apt.activation>` no POM. Para projetos Gradle (Buildship), marque *Project → Properties → Java Compiler → Annotation Processing* e seu *Factory Path*, que precisa listar `resilience4cata-processor` (e sua dependência `resilience4cata-annotations`).

O processador gera o mesmo código com o compilador do Eclipse (ECJ) que com o `javac`: Eclipse, VS Code e IntelliJ IDEA configurado para compilar com Eclipse o usam. Os testes do projeto compilam milhares de fontes anotados aleatórios com ambos e comparam o que o processador escreve.

**VS Code.** O *Extension Pack for Java* usa o compilador do Eclipse por baixo, então as notas do Eclipse se aplicam: projetos Maven pegam `annotationProcessorPaths`, e para projetos Gradle a opção `java.import.gradle.annotationProcessing.enabled` precisa continuar ativada (seu padrão).

Se uma IDE mostra `Resilient.of` falhando com `R4C-RT-003` enquanto o build pela linha de comando funciona, a IDE não executou o processador: reconstrua o projeto depois de ativá-lo.

## JPMS

Em um módulo nomeado, leia `core` (e `config` ou `testkit` se os usar), leia as anotações só em tempo de compilação e exporte para `core` cada pacote que contém tipos anotados, já que ele instancia as classes geradas por reflection:

<!-- not-compiled: um descritor de módulo; o projeto examples não é modular -->
```java
module com.acme.shop {
    requires dev.resilience4cata.core;
    requires static dev.resilience4cata.annotations;
    requires dev.resilience4cata.config;              // optional

    exports com.acme.shop.clients to dev.resilience4cata.core;
}
```

Com `resilience4cata-all`, `requires dev.resilience4cata.all;` substitui os três `requires`: ele lê `core`, as anotações e `config` transitivamente.

Sem o export qualificado, `Resilient.of` falha com [`R4C-RT-005`](../errors.md#r4c-rt-005), cuja mensagem indica a linha a adicionar. `core` requer JSpecify transitivamente, então você não precisa declará-lo.

## Com outros processadores de anotações :id=with-other-annotation-processors

resilience4cata funciona ao lado de Lombok, MapStruct e outros processadores. **Todos os processadores vão no mesmo processor path.** Com Maven, uma vez definido `annotationProcessorPaths`, o `javac` executa apenas os processadores listados ali, então Lombok e MapStruct também precisam estar listados.

A ordem dos processadores não importa. Uma [classe anotada](../guides/annotated-classes.md) que usa Lombok é processada depois que o Lombok a modificou, seja qual for o que roda primeiro, então a subclasse gerada espelha os construtores que o Lombok escreve (`@RequiredArgsConstructor`), e as políticas no nível do tipo cobrem também os getters e setters que ele escreve, como qualquer outro método (veja [Com Lombok](../guides/annotated-classes.md#with-lombok)). Antes da 1.0.0, o Lombok tinha que ser listado primeiro.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    compileOnly 'org.projectlombok:lombok:1.18.38'
    annotationProcessor 'org.projectlombok:lombok:1.18.38'

    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    implementation 'dev.resilience4cata:resilience4cata-core'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")

    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)
    implementation("dev.resilience4cata:resilience4cata-core")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")
}
```

#### **Maven**

```xml
<annotationProcessorPaths>
  <path>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.38</version>
  </path>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.1.0</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

Com MapStruct, adicione `mapstruct-processor` ao mesmo path; se você também usa Lombok, siga as instruções do próprio MapStruct para isso (o artefato `lombok-mapstruct-binding`).

### `-Xlint:all -Werror`

Builds que transformam todo warning em erro funcionam. O `javac` avisa ("No processor claimed any of these annotations") quando uma anotação presente nos fontes não é reivindicada por nenhum processador. Quando o processador do resilience4cata é o único no path, ele reivindica todas as anotações que vê; ao lado de outros processadores, reivindica só as suas (`dev.resilience4cata.annotation.*`), para que os outros continuem vendo todo o resto. Se o warning aparecer mesmo assim, ele lista anotações que outro processador deixou sem reivindicar: adicione `-Xlint:-processing` aos argumentos do compilador.

> [!TIP]
> Declare cada interface anotada no seu próprio arquivo. Uma interface anotada escrita como um segundo tipo de nível superior não público no arquivo de outro tipo faz a classe gerada disparar o warning de lint `auxiliaryclass` do `javac`, que `-Werror` transforma em erro.

## Native image

O GraalVM Native Image não precisa de nenhuma configuração sua; os jars a trazem:

- **O processador escreve reachability metadata para cada classe gerada**, na saída de classes ao lado dela: `META-INF/native-image/dev.resilience4cata.generated/<generated class>/reachability-metadata.json`. Ele registra os construtores públicos da classe para reflection, que é o que `Resilient.of` e `Resilient.create` usam para encontrá-los (uma vez por instância, nunca por chamada). O Native Image lê todo arquivo com esse nome em `META-INF/native-image` no class path. Para `com.acme.PaymentClient`:

  ```json
  {
    "reflection": [
      {
        "condition": {"typeReached": "com.acme.PaymentClient"},
        "type": "com.acme.PaymentClient$Resilient",
        "allPublicConstructors": true
      }
    ]
  }
  ```

  Para uma interface, a entrada se aplica assim que a interface é alcançada (uma implementação dela foi inicializada, o que `Resilient.of` precisa de qualquer forma). Para uma classe anotada, a condição é `dev.resilience4cata.Resilient`, porque `Resilient.create(C.class, ...)` pode rodar antes de `C` ser inicializada; os tipos array dos construtores varargs também são registrados.
- **`core` inicializa suas threads de fundo em tempo de execução.** O watchdog de timeout, o timer de timeout async e o timer de atraso async são iniciados por classes holder preguiçosas; `core` inclui `META-INF/native-image/dev.resilience4cata/resilience4cata-core/native-image.properties` com `--initialize-at-run-time` para esses holders, para que um framework que inicializa classes em tempo de build (o Quarkus faz isso por padrão) nunca coloque uma thread iniciada na imagem. Crie o `ResilienceRegistry` e os serviços envolvidos em tempo de execução, não em um inicializador estático que rode durante o build da imagem.
- Chamar o construtor gerado diretamente (`new StockService$Resilient(registry, warehouse)`) não usa reflection nenhuma.
- **As classes de exceção nomeadas na [configuração](../guides/configuration.md) são você quem registra.** `resilience4cata-config` encontra uma classe escrita em uma chave (`configs.inventory.retry.retryOn=com.acme.OutOfStockException`) com `Class.forName`, que em uma native image só encontra classes registradas para reflection, e a biblioteca não pode registrar as suas classes. Adicione cada uma ao `META-INF/native-image/<group>/<artifact>/reachability-metadata.json` da sua aplicação; caso contrário a configuração falha com [R4C-CFG-003](../errors.md#r4c-cfg-003), cuja mensagem mostra a entrada a adicionar. Classes nomeadas em anotações não precisam de nada (o código gerado as nomeia como literais `.class`). `resilience4cata.properties` chega à imagem sozinho: o Native Image inclui um recurso buscado por um nome constante, como faz `ResilienceConfig.load()`.

  ```json
  {
    "reflection": [
      {"type": "com.acme.OutOfStockException"}
    ]
  }
  ```

A metadata usa o formato de arquivo único do GraalVM para JDK 23 e posteriores (veja [Reachability Metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)). Com um GraalVM mais antigo, registre cada classe `…$Resilient` com seus construtores públicos no seu próprio `reflect-config.json`. O CI do projeto constrói native images de quatro aplicações e executa seus testes dentro delas: uma aplicação Java pura sem framework (`native/java` no repositório: `Resilient.of` e `Resilient.create`, configuração a partir de `resilience4cata.properties`, todas as políticas, métodos `CompletableFuture`, `Mono` e `Flux` e um `Pipeline`), e aplicações das integrações com [Spring Boot](../guides/spring.md#aot), [Micronaut](../guides/micronaut.md#native) e [Quarkus](../guides/quarkus.md#native).

## Solução de problemas :id=troubleshooting

- [`R4C-RT-003`](../errors.md#r4c-rt-003): nenhuma classe gerada. O processador não rodou sobre o tipo, ou o tipo não tem nenhuma anotação de resiliência. O mesmo erro é [R4C-SPR-006](../errors.md#r4c-spr-006) no Spring Boot, [R4C-MN-007](../errors.md#r4c-mn-007) no Micronaut (classes anotadas) e [R4C-QK-008](../errors.md#r4c-qk-008) no Quarkus (em tempo de build).
- [`R4C-RT-004`](../errors.md#r4c-rt-004): `core` e `processor` têm versões diferentes. Use o BOM.
- [`R4C-RT-005`](../errors.md#r4c-rt-005): um módulo nomeado não exporta o pacote para `dev.resilience4cata.core`.
- Os erros de compilação começam com um código como `[R4C-ATR-001]`; cada um tem uma seção com um exemplo errado e a correção na [referência de erros](../errors.md).
- Mais sintomas e correções: [solução de problemas](../guides/troubleshooting.md).
