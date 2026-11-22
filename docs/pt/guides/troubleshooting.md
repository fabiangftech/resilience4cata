# Solução de problemas

Problemas que as pessoas encontram com o resilience4cata, por sintoma, com a causa e a correção.

Todo erro que a biblioteca reporta começa com um código como `[R4C-RT-003]`; a [referência de erros](../errors.md) tem uma seção para cada um, com um exemplo errado e a correção.

## Build e geração de código :id=build

### `Resilient.of` falha: "No generated class … found" :id=no-generated-class

```text
[R4C-RT-003] No generated class com.acme.shop.clients.PriceClient$Resilient found. Make sure that com.acme.shop.clients.PriceClient has at least one resilience annotation, and that the annotation processor runs when it is compiled. With Gradle:
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
With Maven, in the maven-compiler-plugin's <annotationProcessorPaths>:
    <path><groupId>dev.resilience4cata</groupId><artifactId>resilience4cata-processor</artifactId><version>...</version></path>
```

O processor não rodou quando a interface foi compilada, ou a interface não tem nenhuma anotação de resiliência. O processor é obrigatório: não há alternativa por reflection. Nas integrações com frameworks, o mesmo erro aparece na inicialização: [R4C-SPR-006](../errors.md#r4c-spr-006) no Spring Boot, [R4C-MN-007](../errors.md#r4c-mn-007) para uma classe anotada no Micronaut, e [R4C-QK-008](../errors.md#r4c-qk-008) no Quarkus, onde faz o build falhar. As correções abaixo são as mesmas.

- **Gradle:** adicione `annotationProcessor 'dev.resilience4cata:resilience4cata-processor'` (e `annotationProcessor platform(…bom…)` se você usa o BOM, porque o processor path é resolvido separadamente). Para tipos anotados em `src/test`, use também `testAnnotationProcessor` e `testCompileOnly`.
- **Maven:** liste o processor em `<annotationProcessorPaths>` do `maven-compiler-plugin`. Com o maven-compiler-plugin 3.12.0 ou posterior, um `<path>` sem `<version>` pega sua versão do `dependencyManagement` (o BOM importado); versões mais antigas do plugin precisam de um `<version>` explícito.
- **Processor apenas no class path:** desde o JDK 23, o `javac` não roda mais processors encontrados no class path, a menos que seja pedido. Coloque-o no processor path.
- **Verificação:** depois de um build, `build/generated/sources/annotationProcessor/java/main/<package>/<Type>$Resilient.java` (Gradle) ou `target/generated-sources/annotations/…` (Maven) existe.

Veja [R4C-RT-003](../errors.md#r4c-rt-003) e [Instalação](../start/installation.md).

### `[R4C-RT-004]`: a classe gerada não corresponde :id=version-mismatch

A classe gerada foi feita por um processor que este `resilience4cata-core` não consegue executar: geralmente classes geradas antigas de um processor mais velho e incompatível (um de antes da 1.0, ou de outra versão major). Um core mais novo que o processor não é problema dentro de uma versão major; um core mais antigo que o processor é, em vez disso, [R4C-RT-021](../errors.md#r4c-rt-021). Pegue todos os artefatos do BOM e depois faça um build limpo para que as classes geradas antigas desapareçam. Veja [R4C-RT-004](../errors.md#r4c-rt-004).

### `[R4C-RT-005]` em uma aplicação modular :id=jpms

O `core` carrega `<Type>$Resilient` por reflection, então o seu módulo deve exportar (ou abrir) o pacote para ele: `exports com.acme.shop.clients to dev.resilience4cata.core;`. Veja [R4C-RT-005](../errors.md#r4c-rt-005).

### A IDE não roda o processor :id=ide

O build funciona na linha de comando, mas na IDE as classes `$Resilient` estão faltando ou desatualizadas.

- **IntelliJ IDEA:** para projetos Gradle e Maven, o IDEA lê o processor path do build. Se você compila com o compilador próprio do IDEA, habilite *Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable annotation processing*, ou deixe o Gradle fazer o build do projeto (*Build and run using: Gradle*).
- **Eclipse:** o processamento de anotações deve estar habilitado para o projeto (Buildship para Gradle, o suporte a processamento de anotações do m2e para Maven).
- Depois de mudar anotações, refaça o build do módulo: o processor escreve o código gerado quando a interface anotada é compilada.

Veja [Instalação](../start/installation.md) para cada IDE.

### Lombok, MapStruct ou outros processors no mesmo build :id=other-processors

O processor do resilience4cata pode rodar junto com outros annotation processors. Coloque todos no mesmo processor path:

- **Gradle:** uma linha `annotationProcessor` por processor (`org.projectlombok:lombok`, `org.mapstruct:mapstruct-processor`, `dev.resilience4cata:resilience4cata-processor`).
- **Maven:** uma vez definido `<annotationProcessorPaths>`, o Maven roda apenas o que está listado, então liste o Lombok e o MapStruct lá também.
- A ordem não importa: uma [classe anotada](annotated-classes.md#with-lombok) que usa Lombok é processada depois que o Lombok a modificou. Antes da 1.0.0, com o resilience4cata primeiro, uma classe anotada que usava `@RequiredArgsConstructor` falhava ao compilar com um erro como `constructor … in class … cannot be applied to given types`; atualize, ou coloque o Lombok primeiro.

O processor lê apenas as interfaces e classes que levam anotações de resiliência e reivindica apenas as próprias anotações quando outros processors estão presentes, então não esconde anotações deles.

### `-Xlint:all -Werror` falha com "No processor claimed any of these annotations" :id=xlint-processing

O próprio resilience4cata não causa isso: sozinho no processor path, ele reivindica as anotações que vê, e junto com outros processors reivindica apenas `dev.resilience4cata.annotation.*`. Se o aviso lista outras anotações, outro processor as deixou sem reivindicar; adicione `-Xlint:-processing` aos argumentos do compilador, ou corrija isso nesse processor.

### `-Werror` falha com um aviso `auxiliaryclass` em código gerado :id=auxiliary-class

Uma interface anotada declarada como um segundo tipo top-level não público no arquivo-fonte de outro tipo (`Clients.java` contendo `public class Clients` e `interface PriceClient`) é uma *auxiliary class*. O `PriceClient$Resilient` gerado fica em seu próprio arquivo e se refere a ela, então `javac -Xlint:all` avisa `auxiliary class PriceClient in Clients.java should not be accessed from outside its own source file`, e `-Werror` transforma isso em erro. Mova a interface para seu próprio arquivo (`PriceClient.java`), ou aninhe-a como tipo membro da classe.

### Erros de compilação do processor :id=compile-errors

O processor verifica as anotações em tempo de compilação e reporta erros `R4C-APT`, `R4C-ATR`, `R4C-CMP`, `R4C-ASY`, `R4C-CLS`, `R4C-FB` e `R4C-SUB` no elemento anotado. Os comuns:

| Sintoma | Código |
|---|---|
| anotação em um método `static` ou `private` | [R4C-APT-002](../errors.md#r4c-apt-002) |
| anotação em uma interface privada | [R4C-APT-003](../errors.md#r4c-apt-003) |
| uma duração como `"2 s"` ou `"1.5s"` | [R4C-ATR-001](../errors.md#r4c-atr-001) |
| `@Timeout("0")` | [R4C-ATR-002](../errors.md#r4c-atr-002) |
| um método assíncrono que devolve algo diferente de `CompletionStage<T>` / `CompletableFuture<T>` (por exemplo `? super T`) | [R4C-ASY-001](../errors.md#r4c-asy-001) |
| a assinatura do método de fallback não se encaixa | [R4C-FB-003](../errors.md#r4c-fb-003) |
| uma classe anotada que é `final` | [R4C-SUB-001](../errors.md#r4c-sub-001) |

## Comportamento :id=behaviour

### Uma chamada dentro da minha implementação não recebe retry :id=self-invocation

Com `Resilient.of` (o estilo de interface), as policies ficam no wrapper gerado, que chama a sua implementação. Quando a implementação chama um dos seus próprios métodos, a chamada não passa pelo wrapper:

<!-- snippet: troubleshooting-self-invocation-api -->
```java
public interface CatalogApi {

    @Retry(maxAttempts = 3, delay = "0")
    int price(String sku);

    int total(List<String> skus); // not annotated
}
```

<!-- snippet: troubleshooting-self-invocation -->
```java
final class HttpCatalog implements CatalogApi {

    @Override
    public int price(String sku) {
        return fetchPrice(sku); // may fail
    }

    @Override
    public int total(List<String> skus) {
        // this.price(...) calls the implementation directly, not the generated wrapper: no retry here
        return skus.stream().mapToInt(this::price).sum();
    }

    private int failuresLeft = 1; // a flaky remote call: fails once

    private int fetchPrice(String sku) {
        if (failuresLeft-- > 0) {
            throw new IllegalStateException("flaky");
        }
        return 10;
    }
}
```

`catalog.price("a")` recebe retry; `catalog.total(List.of("a"))` falha no primeiro erro, porque `total` chama `this.price` na implementação. Isso é uma propriedade do estilo de interface, não um bug, e não é contornado. Para interceptar autochamadas, anote uma classe em vez disso: a subclasse gerada sobrescreve os métodos, então `this.price(…)` passa pelas policies. Veja [Classes anotadas](annotated-classes.md).

### Anotações na minha classe de implementação não têm efeito :id=implementation-annotations

`Resilient.of(Interface.class, impl, registry)` usa apenas as anotações da interface (e de suas superinterfaces). Anotações na classe de implementação não são lidas pelo wrapper. Anotar a classe a transforma em uma [classe anotada](annotated-classes.md): o processor gera uma subclasse para ela, que você cria com `Resilient.create(Type.class, registry, args…)` ou `new Type$Resilient(registry, args…)`, e a classe então deve cumprir as regras de classes anotadas (não `final`, não `sealed`, não abstrata… veja de [R4C-SUB-001](../errors.md#r4c-sub-001) a [R4C-SUB-008](../errors.md#r4c-sub-008)). Escolha um estilo por tipo.

### Os retries param assim que o breaker abre :id=open-breaker-stops-retries

Esse é o padrão, de propósito. O retry fica fora do circuit breaker (ordem: Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead), e um retry nunca retenta uma `CallNotPermittedException`: uma vez que o breaker rejeita uma tentativa, retentar só seria rejeitado de novo. O retry desiste com `GaveUp(reason = NOT_RETRYABLE)` e relança a `CallNotPermittedException` (ou o fallback roda).

Isso vale independentemente do que `abortOn` e `retryOn` digam, e também com um classifier; não é uma entrada de `abortOn`, então definir a sua própria lista não o remove. Veja [Retry](../policies/retry.md) e [Circuit breaker](../policies/circuit-breaker.md).

### O breaker abre logo após a inicialização :id=breaker-opens-at-startup

Se a dependência está saudável mas o breaker abre nos primeiros segundos após um deploy, olhe a CPU do próprio serviço. Durante um cold start, o JIT compila o código quente e as chamadas são mais lentas do que depois; sob um limite de CPU apertado no contêiner (`limits.cpu` do Kubernetes), a cota do cgroup congela a JVM inteira durante parte de cada período de 100 ms. As chamadas então realmente ultrapassam seu `@Timeout`, e o breaker conta cada `CallTimeoutException` como uma falha da dependência: ele não consegue distinguir um backend lento de um processo lento. Ele fecha de novo quando `waitInOpen` passa e o serviço já aqueceu.

O piloto mediu isso: limitados a meio core, serviços Spring Boot, Micronaut e Quarkus responderam de 3 a 36 % dos pedidos do primeiro minuto a partir de um breaker aberto; com 1 core apenas o Spring o fez, em 2.5 %; sem limite, nenhum ([Benchmarks: serviços sob um limite de CPU](../benchmarks.md#cpu-limit)).

Correções: dê ao serviço folga de CPU na inicialização (um limite maior, ou nenhum, como muitas equipes definem apenas `requests`), ou aqueça-o antes de receber tráfego (uma readiness probe que espera algumas chamadas de aquecimento). Um bulkhead cheio não abre o breaker: suas rejeições nunca são registradas ([Circuit breaker](../policies/circuit-breaker.md#with-other-policies)).

### Um timeout não para o meu código :id=timeout-not-interrupting

Um `@Timeout` síncrono executa a chamada na thread do chamador e a **interrompe** quando o tempo acaba. Código que ignora interrupções continua rodando até retornar por conta própria, e só então o chamador recebe `CallTimeoutException`:

- leituras clássicas bloqueantes de `java.net.Socket` / `InputStream`, e bibliotecas construídas sobre elas;
- loops ocupados que nunca verificam `Thread.interrupted()`;
- código que captura `InterruptedException` e segue em frente.

Correções: configure os timeouts de I/O do próprio cliente (timeouts de conexão e de leitura; `java.net.http.HttpClient`, canais NIO e a maioria dos clientes modernos respondem a interrupções ou têm seus próprios timeouts), verifique `Thread.interrupted()` em loops longos e restaure a flag de interrupção quando capturar `InterruptedException`. Para métodos assíncronos, o stage devolvido falha com `CallTimeoutException` no tempo certo, e o stage da chamada é cancelado se for um `Future`; se o trabalho por trás dele para depende desse código.

Os timeouts também são grosseiros por projeto: nunca adiantados, no máximo uns dois ticks do watchdog atrasados (um tick é o menor timeout em uso / 16, entre 1 e 50 ms) enquanto o processo tem CPU; sob um limite de CPU do contêiner, uma JVM estrangulada só é interrompida quando volta a rodar. Veja [Timeout](../policies/timeout.md).

### Minha configuração externa não é aplicada :id=config-not-applied

Verifique, nesta ordem:

1. **O valor é sobrescrito por algo de maior precedência.** Do menor para o maior: padrões embutidos, `defaults.*`, `configs.<name>.*`, atributos da anotação, `instances.<id>.*`. Uma chave `defaults.*` não altera um atributo que a anotação define explicitamente; use `instances.<id>.*` para isso. Veja [precedência](../reference/configuration.md#precedence).
2. **A fonte não é lida.** `ResilienceConfig.load()` lê apenas o recurso de classpath `resilience4cata.properties`, variáveis de ambiente (`R4C_…`) e propriedades de sistema **com o prefixo `r4c.` ou `resilience4cata.`**; outros arquivos precisam de `ResilienceConfig.builder().file(…)` ou `.classpath(…)`.
3. **A configuração não é aplicada ao registry.** Envolva seus serviços com o registry construído por `config.applyTo(ResilienceRegistry.builder()).build()` (ou `config.toRegistry()`).
4. **O id da instância não corresponde.** Uma chave `instances.<id>` cujo id não corresponde a nada fica silenciosamente sem uso até você perguntar. `config.describe()` lista cada valor efetivo com sua origem; `config.verifyInstances(registry)`, chamado depois de envolver seus serviços, falha com ids que não correspondem a nenhuma instância e sugere o mais próximo:

<!-- snippet: troubleshooting-verify-instances -->
```java
ResilienceConfig config = ResilienceConfig.builder().properties("app.properties", properties).build();
System.out.println(config.describe()); // every effective value and where it comes from
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
Payments payments = Resilient.of(Payments.class, order -> "ok", registry);
config.verifyInstances(registry); // after every service is wrapped: fails on ids that match nothing
```

Ids derivados têm a forma `com.acme.Api#get(java.lang.String)` e devem ficar entre aspas nas chaves: `instances."com.acme.Api#get(java.lang.String)".timeout.value=2s`. Veja [R4C-CFG-013](../errors.md#r4c-cfg-013) e [Chaves de configuração](../reference/configuration.md).

5. **Você esperava um recarregamento.** A configuração é lida uma vez, quando o registry e suas instâncias são construídos; não há hot reload.

### `IllegalArgumentException [R4C-REG-009]` de `registry.circuitBreaker(id)` :id=handle-not-found

O id está errado, ou a instância ainda não existe: as instâncias são criadas quando a interface é envolvida (`Resilient.of`, `Resilient.create`) ou o `Pipeline` é construído. A mensagem sugere o id mais próximo. Veja [R4C-REG-009](../errors.md#r4c-reg-009).

### `IllegalStateException [R4C-REG-008]` ao envolver :id=shared-name

Dois métodos compartilham um `name` (ou um id de pipeline), mas resolvem para configurações diferentes. Métodos que compartilham uma instância devem concordar em todos os atributos; dê a eles nomes diferentes, ou os mesmos atributos. Veja [R4C-REG-008](../errors.md#r4c-reg-008).
