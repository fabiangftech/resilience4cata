# 02 · Mecanismos de anotação: transformar anotações em comportamento sem um framework

> **Histórico.** Esta pesquisa é anterior à implementação e é mantida como foi escrita; para saber o que a biblioteca faz hoje, veja [funcionalidades](../policies/README.md), [arquitetura](../architecture.md) e [benchmarks](../benchmarks.md).

> **Fase 1, apenas pesquisa.** Sem código de produção. Este documento compara as formas como uma biblioteca Java 21+ independente (sem Spring, sem Quarkus, sem CDI) pode transformar `@CircuitBreaker`, `@Retry` e anotações semelhantes em comportamento em execução. Termina com uma recomendação ordenada e um esboço da API voltada ao usuário.
>
> **Como ler as evidências:**
> - **[V] Verificado**: conferido com uma fonte primária durante esta pesquisa (link indicado), ou medido por nós (veja [§3](#3-our-own-measurements-jmh)).
> - **[I] Inferido**: vem de julgamento de engenharia ou de conhecimento prévio e não foi reconferido com uma fonte para este documento. Trate como uma hipótese a confirmar na fase 2.
>
> Versão em espanhol: [`docs/es/research/02-annotation-mechanisms.md`](../../es/research/02-annotation-mechanisms.md).

---

## 0. Resumo :id=_0-tldr

1. **Recomendado: processamento de anotações em tempo de compilação (APT) que gera código-fonte Java simples**, com uma pequena fachada de runtime (`Resilient.of(...)`) que encontra a classe gerada uma única vez, quando a instância é criada. Por chamada, custa mais ou menos o mesmo que código escrito à mão: sem reflexão, sem boxing em `Object[]` e com um call site monomórfico por método gerado. Inicia instantaneamente, funciona no GraalVM native-image sem configuração, produz stack traces legíveis e detecta erros como uma assinatura de fallback errada ou um nome de configuração desconhecido em tempo de compilação.
2. **A forma do código gerado importa tanto quanto o mecanismo.** Na nossa execução JMH, o código gerado que chama a política por meio de um `execute(Supplier)` compartilhado que recebe uma lambda foi **cerca de 2× mais lento** do que o código gerado com o protocolo da política inline, assim que vários serviços passaram a compartilhar o caminho (14,2 ns vs 7,3 ns para 4 chamadas) **[V, medido]**. O módulo core deve, portanto, oferecer um "protocolo de permissão" de baixo nível e sem alocação (acquire, depois onSuccess ou onError) para o código gerado, ao lado da API funcional que oferece às pessoas.
3. **`java.lang.reflect.Proxy` é 5–15× mais lento por chamada e aloca** (16–64 B por chamada na nossa execução). Só lida com interfaces. É aceitável apenas como **alternativa opcional**.
4. **ByteBuddy em runtime é tão rápido por chamada quanto o código-fonte gerado** (emite bytecode real), mas a primeira instância levou **cerca de 200 ms vs 0,37 ms** do código-fonte gerado **[V, medido]**. Também acrescenta uma dependência de 9 MB, e o native-image precisa de passos extras de build. Não é o padrão; no máximo poderia sustentar um módulo opcional.
5. **Agentes Java e weaving do AspectJ** têm bom desempenho por chamada, mas a pior experiência de usuário: flags da JVM, um aviso de dynamic attach desde o JDK 21, um weaver que precisa acompanhar cada novo JDK e um compilador fora do padrão. Rejeitados para o core.
6. A **Class-File API só é final no JDK 24** **[V]**, então não é utilizável com a nossa base Java 21 sem um JAR multi-release. Vale lembrar dela para quando a base passar para o Java 25 LTS.

---

## 1. O problema :id=_1-the-problem

Para cada método anotado precisamos, sem um contêiner:

1. **Descobrir** as anotações (`@CircuitBreaker("payments")`, `@Retry(maxAttempts = 3)`, `@Fallback(...)`).
2. **Resolver a configuração**: uma configuração nomeada, depois os padrões globais, depois as sobrescritas por método vindas dos atributos da anotação, depois a configuração externa. (O agente de design de API e configuração é dono das regras de precedência. Este documento só se preocupa com *quando* a resolução acontece: em tempo de build, ao envolver ou na chamada.)
3. **Construir uma instância** cujo método executa a cadeia de políticas em volta do código do usuário.
4. **Entregar essa instância ao usuário**, sem um contêiner de DI para injetá-la.

O custo por chamada é decidido no passo 3: como o código que envolve é produzido e o que o JIT consegue fazer com ele.

### Critérios de avaliação :id=evaluation-criteria

| Critério | Por que importa para nós |
|---|---|
| Overhead por chamada | Nosso objetivo principal ("mais rápido que o Resilience4j"). Inclui inlining do JIT, call sites megamórficos, reflexão, boxing e alocação. |
| Custo de inicialização / da primeira chamada | Ferramentas de linha de comando, serverless, testes. |
| GraalVM native-image | Cada vez mais esperado de bibliotecas. Definir classes em runtime não é suportado ali. |
| Depurabilidade | Stack traces, execução passo a passo, "que código realmente roda?". |
| Complexidade de build para os usuários | Não temos um plugin de framework atrás do qual esconder a complexidade. |
| Limitações | Classes final, métodos privados, interfaces vs classes, autoinvocação. |
| Síncrono / `CompletableFuture` / virtual threads | Os três precisam funcionar. |

---

## 2. As opções :id=_2-the-options

### 2.1 Processamento de anotações em tempo de compilação (APT) gerando código-fonte :id=_21-compile-time-annotation-processing-apt-generating-source

**Como funciona.** Um `javax.annotation.processing.Processor` roda dentro do `javac`, lê `@CircuitBreaker` e as anotações relacionadas do modelo `Element`/`TypeMirror` e escreve novos arquivos `.java` pelo `Filer`. Esses arquivos são compilados no mesmo build. Nada roda em runtime além do código que foi gerado.

**Trabalhos anteriores.**
- **Dagger, MapStruct, Immutables**: processadores clássicos que geram código-fonte (sem AOP). **[I]**
- **Avaje Inject** gera classes `MyService$Proxy` que **estendem a classe-alvo e sobrescrevem os métodos anotados**. As sobrescritas executam os interceptadores e depois chamam `super`. Os aspectos são declarados com `@Aspect`, `AspectProvider<T>` e `MethodInterceptor`, e `@AOPFallback` nomeia um método de recuperação. **[V]** ([avaje.io/inject](https://avaje.io/inject/#aspects))
- **Micronaut AOP**: por padrão gera uma **subclasse** do alvo em tempo de compilação e chama o original via `super`. `@Around(proxyTarget = true)` faz com que gere um proxy delegador em vez disso. A documentação do Micronaut diz que o padrão de subclasse é "more efficient" e permite que a interceptação funcione para chamadas feitas de dentro da classe. **[V]** ([javadoc de `@Around`](https://docs.micronaut.io/4.8.2/api/io/micronaut/aop/Around.html)). O Micronaut também traz `@Retryable` e `@CircuitBreaker` construídos sobre esse mecanismo, e tem um auxiliar `InterceptedMethod` para tipos de retorno `CompletionStage` e reativos. **[I]** ([guia de AOP do Micronaut](https://docs.micronaut.io/latest/guide/#aop))

**Ferramentas de geração.**
- **Palantir JavaPoet** (`com.palantir.javapoet:javapoet`) é o fork mantido. Seu README descreve o JavaPoet da Square como "no longer actively maintained" e acrescenta builders para records e tipos sealed. **[V]** ([palantir/javapoet](https://github.com/palantir/javapoet), [nota de descontinuação da Square via espelho](https://docsearch.algolia.com/mcp/docs/repo/square/javapoet))
- Alternativa: templates escritos à mão, sem dependência. O código gerado é pequeno e regular, então isso é realista e mantém o processador sem dependências. **[I]**
- O JAR do processador é necessário **apenas em tempo de compilação** (configuração `annotationProcessor`), então o JavaPoet nunca chega ao classpath de runtime do usuário. **[I]**

**Processamento incremental no Gradle [V]** ([documentação do Gradle](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing)):
- Um processador se declara em `META-INF/gradle/incremental.annotation.processors` como `isolating`, `aggregating` ou `dynamic`.
- **Isolating** é o mais rápido. Cada arquivo gerado deve ter **exatamente um elemento de origem**, e as decisões só podem usar o que é alcançável a partir desse elemento. Um `Foo$Resilient` por `Foo` anotado se encaixa perfeitamente nisso.
- **Aggregating** reprocessa todos os arquivos anotados. Precisaríamos dele se gerássemos um único registro, como um arquivo `META-INF/services`. **Consequência de design:** encontrar as classes geradas **por convenção de nomes** (`Foo` → `Foo$Resilient`) em vez de por um registro agregado, para que o processador possa continuar *isolating*.
- Outras restrições: usar apenas a API `Filer`, evitar APIs internas do compilador como `com.sun.source.util.Trees` e lembrar que os nomes de parâmetros só estão disponíveis com `-parameters`.

**O JDK 23 mudou o padrão do javac [V]:** o processamento de anotações deixou de rodar implicitamente a partir do classpath. Ele precisa de configuração explícita, como `-processorpath` ou `-proc:full`. ([notas de versão do JDK 23](https://www.oracle.com/java/technologies/javase/23-relnote-issues.html), [revisão do JDK-8321314](https://mail.openjdk.org/pipermail/compiler-dev/2024-May/026675.html)). A configuração `annotationProcessor` do Gradle passa um processor path explícito, então os usuários do Gradle não devem ser afetados **[I]**. Mesmo assim, nossa documentação deve mostrar a linha `annotationProcessor(...)`, e os usuários do Maven precisam de `annotationProcessorPaths`.

**Estilos de wrapper que o processador pode gerar:**

| Estilo | Forma gerada | Prós | Contras |
|---|---|---|---|
| **Wrapper delegador** | `final class Foo$Resilient implements Foo { final Foo delegate; ... }` | Funciona para **interfaces**. Envolve *qualquer* instância, incluindo as de outro contêiner de DI, de uma factory de terceiros ou um dublê de teste. Nunca intercepta autochamadas, o que é previsível. | Um dispatch de interface extra até o delegate. Só os métodos de interface podem ser decorados. |
| **Subclasse** | `final class Foo$Resilient extends Foo { ... super.call() ... }` | Funciona para **classes concretas**. Medida como um pouco mais rápida que a delegação ([§3](#3-our-own-measurements-jmh)). Pode interceptar a autoinvocação, se quisermos. | A classe não pode ser `final`, e os métodos não podem ser `final`, `private` ou `static`. Cada construtor precisa ser espelhado. O *usuário* precisa criar a subclasse em vez de `new Foo(...)`. |

**Limites rígidos do APT [I, semântica padrão do Java]:**
- Não pode modificar a própria classe anotada; o APT só pode *acrescentar* arquivos. Portanto, não pode decorar métodos `private`, `final` ou `static`, nem classes final ao gerar uma subclasse.
- Só enxerga o código compilado no mesmo build: não pode decorar as classes de um JAR de terceiros. Esse caso é coberto pelo wrapper delegador sobre uma interface, ou pela API funcional.
- Não suporta Kotlin, a não ser que se escreva um port para KSP ou kapt. Fora do escopo.
- As mensagens de erro do processador *são* a experiência do usuário, então precisam ser excelentes.

**Como os usuários obtêm a instância.** Opções, da mais explícita à mais conveniente:
1. `new PaymentClient$Resilient(delegate, registry)`: explícita, mas um nome com `$` no código do usuário é feio.
2. Uma factory estática gerada: `PaymentClientResilience.wrap(delegate, registry)`. É type-safe e sem nenhuma mágica.
3. Uma fachada de runtime, `Resilient.of(PaymentClient.class, delegate)`. Ela encontra `PaymentClient$Resilient` **uma única vez** via `Class.forName` mais um cache `ClassValue`, chama um construtor gerado ou um `MethodHandle` criado uma única vez e retorna a instância. **Não há reflexão por chamada.** O processador também pode emitir `META-INF/native-image/.../reflect-config.json` para esse construtor, então o native-image não precisa de nenhuma configuração do usuário. **[I]**

   Recomendamos oferecer (3) como ponto de entrada documentado e (2) para quem não quer nenhuma busca.

**Avaliação.**

| Critério | Avaliação |
|---|---|
| Overhead por chamada | **O melhor da categoria.** É código Java comum: um call site monomórfico por método gerado, então o JIT consegue fazer inline da política e do método do usuário. O tipo de retorno é conhecido estaticamente, então não há boxing nem `Object[]`. Medido em 2,4–2,6 ns vs 1,2 ns de uma chamada direta ([§3](#3-our-own-measurements-jmh)). **[V]** |
| Inicialização | **A melhor.** Carregar a primeira instância custou cerca de 0,37 ms na nossa execução, que é só o carregamento de classes. **[V]** |
| native-image | **Nativo.** Sem proxies e sem bytecode em runtime. No máximo precisa de um construtor reflexivo, e podemos gerar esses metadados. **[I]** |
| Depurabilidade | **A melhor.** O código-fonte gerado fica em `build/generated/sources/annotationProcessor`, então os usuários podem abri-lo e percorrê-lo passo a passo. Os frames ficam como `PaymentClient$Resilient.charge(PaymentClient$Resilient.java:41)`. **[I]** |
| Complexidade de build | Baixa: uma linha de dependência `annotationProcessor`. As IDEs (IntelliJ, Eclipse) executam processadores sem configuração. **[I]** |
| Síncrono / assíncrono / VT | O processador vê o **tipo de retorno declarado** em tempo de compilação e emite código especializado para resultados síncronos, `void` e `CompletionStage`/`CompletableFuture` (anexando callbacks em vez de bloquear). Não precisa de verificações `instanceof` em runtime. Virtual threads não precisam de geração de código especial: o código síncrono simplesmente bloqueia em uma virtual thread. As preocupações com pinning são do core (veja o agente de concorrência). **[I]** |
| Benefício extra | **Validação em tempo de compilação**: um método de fallback ausente, uma assinatura de fallback que não bate ou anotações conflitantes viram erros de compilação. A integração Spring do Resilience4j resolve `fallbackMethod` pelo nome via reflexão em runtime **[I]** ([código do resilience4j](https://github.com/resilience4j/resilience4j)). |

### 2.2 Proxies dinâmicos em runtime (`java.lang.reflect.Proxy`) :id=_22-runtime-dynamic-proxies-javalangreflectproxy

**Como funciona.** `Proxy.newProxyInstance(loader, interfaces, handler)` cria uma classe proxy em runtime. Toda chamada vai para `InvocationHandler.invoke(proxy, Method, Object[] args)`.

**Fatos:**
- Lida **apenas com interfaces**. **[I, contrato da API do JDK]**
- Desde o **JDK 18 (JEP 416)**, `Method.invoke` é implementado sobre method handles. É **43–57% mais rápido quando o `Method` é um `static final` que pode ser constant-folded**, mas **mais lento quando não é** (acesso a campos 51–77% mais lento em microbenchmarks). **[V]** ([JEP 416](https://openjdk.org/jeps/416)). Em um handler de proxy, o `Method` chega como *argumento*, então em geral **não** é uma constante no site de `invoke`. Este é o caso ruim.
- O handler é **compartilhado**: um único corpo de `InvocationHandler.invoke`, e o `Method.invoke` dentro dele, atendem todos os métodos de todos os serviços com proxy. Esses sites ficam **megamórficos** em qualquer aplicação real, o que impede o inlining. **[I, consistente com a nossa medição poly4]**
- Os argumentos são empacotados em um `Object[]`, os primitivos sofrem boxing e as exceções checadas chegam envolvidas em `InvocationTargetException` ou `UndeclaredThrowableException`. **[I]**
- **native-image:** os proxies precisam ser gerados no build da imagem. O Native Image detecta chamadas a `newProxyInstance` automaticamente **apenas quando o array de interfaces é uma constante ou é alocado no mesmo método**. Caso contrário, é preciso configuração de proxy em JSON. **[V]** ([documentação de Dynamic Proxy do GraalVM](https://www.graalvm.org/jdk17/reference-manual/native-image/dynamic-features/DynamicProxy/), [referência de metadados](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)). Uma biblioteca que cria proxies de interfaces do *usuário* recebidas em runtime cai no caso que precisa de configuração.
- **Variante com MethodHandle:** guardar em cache um `MethodHandle` vinculado e com spread por `Method` **não** ajudou na nossa execução. Foi mais lento que o `Method.invoke` simples (25 ns vs 16 ns mono, 91 ns vs 37 ns poly) por causa da busca no mapa e do handle não constante **[V, medido]**. `MethodHandle`s não constantes não sofrem inline, o que bate com o que engenheiros do OpenJDK já disseram ([thread mlvm-dev](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006826.html)) **[V]**.
- **LambdaMetafactory** pode transformar um `Method` em uma implementação real de interface funcional, evitando a reflexão depois que a lambda é criada. Execuções JMH mais antigas a mostram perto de uma chamada direta quando o inlining dá certo ([Timefold](https://timefold.ai/blog/java-reflection-but-much-faster), [mlvm-dev](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006827.html)) **[V]**. Mas ela não elimina o handler compartilhado e megamórfico, então melhora a chamada ao *alvo* e deixa o problema de dispatch onde está. **[I]**

| Critério | Avaliação |
|---|---|
| Overhead por chamada | **Ruim.** 16 ns mono / cerca de 9 ns por chamada em poly4, vs 2–2,6 ns do código gerado; 16–64 B alocados por chamada. **[V, medido]** |
| Inicialização | Primeira instância de proxy cerca de 2,4 ms. **[V, medido]** |
| native-image | Precisa de configuração para interfaces passadas em runtime. **[V]** |
| Depurabilidade | Mediana: `jdk.proxy1.$Proxy7.charge(Unknown Source)`, depois frames do handler, depois frames de reflexão, além de exceções envolvidas. **[I]** |
| Complexidade de build | Nenhuma. |
| Limitações | Apenas interfaces. |

**Veredito:** uma **alternativa razoável sem nenhuma configuração de build** para interfaces, por exemplo um módulo adicional `dynamic`, mas nunca o caminho padrão.

### 2.3 Geração de bytecode em runtime (ByteBuddy, Class-File API, hidden classes) :id=_23-runtime-bytecode-generation-bytebuddy-class-file-api-hidden-classes

**ByteBuddy.**
- Gera bytecode real em runtime, como subclasses ou implementações de interfaces. `Advice` *copia* o código do advice para dentro do método gerado, enquanto `MethodDelegation` emite uma chamada direta a um interceptador. **[V]** (comportamento confirmado pelo desenho e pelos resultados do nosso benchmark)
- Por chamada é **excelente**, tão rápido quanto ou um pouco mais rápido que nossas variantes de código-fonte gerado na nossa execução (2,0 ns mono, cerca de 1,5 ns por chamada poly4). Parte dessa vantagem vem do próprio benchmark: nossas variantes ByteBuddy leem a guarda de um campo `static final` (constant-folded) e as variantes de código-fonte gerado leem um campo de instância. Veja [§3](#3-our-own-measurements-jmh). **[V, medido + I para a explicação]**
- **A inicialização é cara:** cerca de **200 ms** para criar a primeira instância em uma JVM nova, a maior parte carregando e inicializando o próprio ByteBuddy. **[V, medido]**
- **Peso da dependência:** `byte-buddy-1.18.3.jar` tem **9,2 MB** **[V, arquivo local]**. É possível fazer shading, mas é muita coisa para colocar no classpath de todo usuário.
- **Acompanhar o JDK:** o Byte Buddy publica uma tabela de versão mínima por versão do formato class-file do Java. Desde a 1.17.0 usa a Class-File API para acompanhar novos JDKs, a menos que apareçam novos recursos de class-file. **[V]** ([README do raphw/byte-buddy](https://github.com/raphw/byte-buddy))
- **native-image:** não se pode definir classes em runtime. Os caminhos suportados são gerar as classes em tempo de build, ou as "predefined classes" do agente de rastreamento, em regime de melhor esforço, que a documentação diz "not guaranteed to work". **[V]** ([metadados do GraalVM](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)). O ByteBuddy tem um **plugin Gradle para transformação em tempo de build** (`net.bytebuddy.byte-buddy-gradle-plugin`), o que o tornaria uma ferramenta de weaving (veja §2.4). **[V]** ([Gradle Plugin Portal](https://plugins.gradle.org/plugin/net.bytebuddy.byte-buddy-gradle-plugin))
- **Depurabilidade:** não há código-fonte. Os frames ficam como `PaymentClient$ByteBuddy$a1b2c3.charge(Unknown Source)`, e entrar passo a passo no advice é desajeitado. **[I]**
- **Acesso às classes do usuário:** para criar uma subclasse de uma classe do usuário no pacote dela, ou para definir um nestmate, é preciso um `Lookup` com o acesso certo. Com JPMS, isso significa que o módulo do usuário precisa fazer `open` do pacote para nós. **[I]**

**A API `java.lang.classfile`.**
- Em preview no JDK 22 (JEP 457) e no JDK 23 (JEP 466), e **final no JDK 24 (JEP 484)**. **[V]** ([commit do JDK-8334714](https://gitea.hb.dhbw-stuttgart.de/stan/jdk-24/commit/84ffb64cd73f8af11cf3670c6f19d282c2ac6961), [e-mail de targeting no jdk-dev](https://mail.openjdk.org/pipermail/jdk-dev/2024-October/009461.html), [Inside Java Newscast #81](https://nipafx.dev/inside-java-newscast-81/))
- Como nosso mínimo é o Java 21, só poderíamos usá-la a partir da camada `META-INF/versions/24` de um JAR multi-release. Ela também tem os mesmos problemas de native-image e de inicialização que qualquer geração em runtime. **Passa a ser relevante se a base subir para o 25 (LTS)**, por exemplo para uma alternativa de runtime sem dependências. **[I]**

**Hidden classes (JEP 371, JDK 15).** São definidas com `Lookup.defineHiddenClass`. Não podem ser descobertas pelo nome, podem ser descarregadas e podem entrar no nest da classe de lookup para acessar membros privados. O JEP cita `java.lang.reflect.Proxy` como candidato a usuário. **[V]** ([JEP 371](https://openjdk.org/jeps/371)). As hidden classes são um *contêiner* para código gerado em runtime, não um gerador, e trazem os mesmos limites de native-image.

| Critério | Avaliação |
|---|---|
| Overhead por chamada | **Excelente** (comparável ao código-fonte gerado). **[V]** |
| Inicialização | **Ruim** (cerca de 200 ms). **[V]** |
| native-image | **Ruim**, a menos que a geração passe para o tempo de build. **[V]** |
| Depurabilidade | Fraca. **[I]** |
| Complexidade de build | Nenhuma em runtime; uma dependência de 9 MB. |
| Limitações | As mesmas regras de subclasse que o APT (nada de final ou private), além do acesso a módulos. |

### 2.4 Agente Java / weaving em tempo de carga, e weaving em tempo de compilação :id=_24-java-agent--load-time-weaving-and-compile-time-weaving

**Agente Java (agente ByteBuddy, LTW do AspectJ).**
- Um agente reescreve as classes do usuário à medida que são carregadas. Ele *consegue* instrumentar classes final e métodos privados, e até JARs de terceiros. **[I]**
- Os usuários precisam acrescentar `-javaagent:...` a toda JVM: em produção, nos testes, na IDE e nos contêineres. **[I]**
- Desde o **JDK 21 (JEP 451)**, carregar um agente em uma JVM em execução (self-attach) imprime um aviso, e uma versão futura vai proibir isso por padrão (é preciso `-XX:+EnableDynamicAgentLoading` para ativar). Agentes carregados na inicialização não são afetados. **[V]** ([JEP 451](https://openjdk.org/jeps/451)). Então o truque de self-attach que algumas bibliotecas usam para evitar a flag está com os dias contados.
- **native-image:** não há carregamento de classes em runtime, então isso não se aplica. **[I]**
- Por chamada é bom, porque o advice pode sofrer inline dentro do método. A inicialização é ruim: o weaver varre e transforma as classes à medida que são carregadas. **[I]**

**Weaving em tempo de compilação (`ajc` do AspectJ, plugin Gradle do ByteBuddy).**
- O número de versão do AspectJ acompanha a versão do Java que ele suporta: 1.9.22 para Java 22, 1.9.23 para Java 23. **[V]** ([notas da 1.9.23](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.23.html), [notas da 1.9.22](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.22.html)). Desde a 1.9.21, o `ajc` precisa de **JDK 17+ para rodar**. **[V]** ([notas da 1.9.21](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.21.html)). Cada novo JDK pode, portanto, bloquear os usuários até o AspectJ alcançá-lo. **[I]**
- No Gradle isso passa por um plugin de terceiros (`io.freefair.aspectj` / `...post-compile-weaving`), cuja documentação os usuários relatam ser escassa. **[V]** ([fórum do Gradle](https://discuss.gradle.org/t/how-to-use-the-io-freefair-aspectj-post-compile-weaving/27954))
- Substitui ou pós-processa a saída do `javac`, o que interage mal com compilação incremental, Lombok, outros processadores e builds da IDE. **[I]**
- O advice `around` do AspectJ pode alocar objetos de closure, a menos que o weaver consiga fazer inline do advice. É uma fonte extra de custo por chamada. **[I]**
- É amigável ao native-image, porque tudo acontece em tempo de build. **[I]**

As anotações do Resilience4j dependem do Spring AOP ou do AspectJ exatamente por esse motivo: sem um framework ou um weaver, não há nada que as aplique. **[V]** ([Baeldung](https://www.baeldung.com/spring-boot-resilience4j), [guia da oneuptime](https://oneuptime.com/blog/post/2026-01-25-circuit-breakers-resilience4j-spring/markdown))

**Veredito:** rejeitado para o core. Essas ferramentas são poderosas, mas impõem a maior cerimônia de build e de runtime aos usuários de uma biblioteca que deveria ser leve.

### 2.5 Híbridos :id=_25-hybrids

| Híbrido | Descrição | Avaliação |
|---|---|---|
| **A. APT + core funcional + busca por convenção** *(recomendado)* | O processador gera `Foo$Resilient` (subclasse ou delegate). `Resilient.of(...)` o encontra pelo nome, uma única vez. Os usuários também podem chamar a API funcional/programática diretamente (`breaker.call(() -> ...)`) para código que não podem anotar. | Caminho mais rápido por padrão e nenhuma mágica em runtime. A API funcional cobre código de terceiros. |
| **B. A + módulo `dynamic` opcional** | Quando nenhuma classe gerada é encontrada, um módulo *opcional* recorre ao `Proxy` para interfaces. Sem esse módulo, `Resilient.of` falha imediatamente com uma mensagem como "did you add the annotation processor?". | Ajuda na prototipagem e nos testes, enquanto o core continua sem reflexão. A alternativa nunca deve ser escolhida em silêncio, ou os usuários teriam 10× de overhead sem saber. |
| **C. APT gera apenas metadados + um motor em runtime os lê** | O processador emite descritores por método (nomes de configuração, tipo de retorno, handles de fallback), e um interceptador genérico em runtime os executa. | Elimina a varredura de anotações, mas traz de volta um caminho de dispatch compartilhado e megamórfico. Mensuravelmente mais lento (nosso resultado `aptLambda` poly4). Não recomendado. |
| **D. APT + plugin ByteBuddy em tempo de build** | Para usuários que precisam decorar classes final ou métodos privados no lugar. | Possível mais tarde como complemento. Pesado demais para o padrão. |
| **(Rejeitado) reescrita da AST do javac à la Lombok** | Modificar o corpo do método anotado dentro do `javac`. | Usa internos do compilador sem suporte, quebra nas atualizações do JDK e viola as regras de processamento incremental do Gradle (nada de `Trees`). **[V para a regra do Gradle, I no restante]** |

---

## 3. Nossas próprias medições (JMH) :id=_3-our-own-measurements-jmh

Não encontramos nenhuma comparação JMH pública, recente e em pé de igualdade entre esses mecanismos. As mais citadas têm uma década ou não usam JMH ([neoremind/dynamic-proxy](https://github.com/neoremind/dynamic-proxy), medições de uma única execução em Java 6/7/8). Então escrevemos um harness JMH **descartável**. É andaime de pesquisa, não código de produção, e é mantido fora do repositório; o código principal está no [Apêndice A](#appendix-a-benchmark-harness-condensed).

**Configuração [V]:** JMH 1.37, OpenJDK 25.0.3 (build da Microsoft), Apple M1 (8 núcleos), macOS 14. Tempo médio, 2 forks × 5 × 1 s de medição após 3 × 1 s de aquecimento, `-prof gc`. O argumento é `100_000`, fora do cache de `Integer`, então o boxing é real. A "política" é uma guarda mínima (uma leitura volatile na entrada, um contador em caso de erro), então **só o custo de dispatch é medido**, não a lógica do circuit breaker.

- **mono**: um único tipo de serviço passando pelo mecanismo.
- **poly4**: quatro implementações diferentes de serviço decoradas pelo mesmo mecanismo e chamadas em sequência. Isso fica mais perto de uma aplicação real com muitos serviços decorados. O número é o tempo das **quatro chamadas juntas**.

| Variante | mono ns/op | mono B/op | poly4 ns/op (4 chamadas) | poly4 B/op |
|---|---:|---:|---:|---:|
| Chamada direta (referência) | 1.18 | 0 | 2.38 | 0 |
| **APT, wrapper delegador, protocolo inline** | 2.59 | 0 | 7.29 | 0 |
| **APT, subclasse, protocolo inline** | 2.36 | 0 | 6.95 | 0 |
| Wrapper APT chamando um `execute(Supplier)` compartilhado (lambda) | 2.47 | 0 | **14.23** | 0 |
| Subclasse ByteBuddy + `Advice` | 1.99 | 0 | 6.05 | 0 |
| Subclasse ByteBuddy + `MethodDelegation` (`@SuperCall`) | 1.99 | 0 | 5.97 | 0 |
| `Proxy` + `MethodHandle` com spread em cache | 25.15 | 56 | 91.53 | 224 |
| `Proxy` + `Method.invoke` | 16.34 | 16 | 37.30 | 64 |

As barras de erro ficaram abaixo de ±1,1 ns em todas as linhas. Os resultados JSON brutos foram guardados junto com o harness.

| Primeira instância em uma JVM nova (`SingleShotTime`, 10 forks) | µs |
|---|---:|
| Wrapper gerado por APT | **366 ± 11** |
| `Proxy` | 2,437 ± 389 |
| ByteBuddy `Advice` | 201,397 ± 9,364 |
| ByteBuddy `MethodDelegation` | 202,105 ± 26,270 |

**Interpretação:**
1. **Todo mecanismo que produz bytecode real, seja a partir de código-fonte ou em runtime, cai na mesma faixa**: cerca de 1–1,5 ns por chamada acima de uma chamada direta. Entre eles, o mecanismo importa muito menos do que *o que o código gerado faz*. **[V]**
2. **O dispatch compartilhado baseado em lambda praticamente dobra o overhead em uso polimórfico** (14,2 vs 7,3 ns). No caso mono o JIT faz inline de tudo, o que esconde o problema; é por isso que microbenchmarks de um único serviço enganam. **[V]** Este é o argumento mais forte para o código gerado chamar um **protocolo por método, inline**, em vez de afunilar tudo por um único `execute(Supplier)` genérico. Também sugere onde o estilo de decoradores do Resilience4j perde tempo sob um polimorfismo realista (a confirmar pelo agente de concorrentes). **[I]**
3. A pequena vantagem do ByteBuddy sobre o APT provavelmente vem da **guarda `static final`** nessas variantes: o JIT a trata como constante, enquanto as variantes APT leem um campo de instância. O código gerado poderia obter o mesmo benefício onde quer que uma política possa ser vinculada a um campo `static final`, por exemplo com configuração fixada em tempo de compilação, mas registries por instância não podem fazer isso. Vale explorar na fase 2. **[I]**
4. **A subclasse vence a delegação por pouco** (2,36 vs 2,59, 6,95 vs 7,29), porque uma chamada a `super` é vinculada estaticamente, enquanto uma chamada ao delegate é uma chamada de interface. **[V]**
5. A escape analysis eliminou o `Callable` do `@SuperCall` e o boxing nas variantes ByteBuddy e lambda neste benchmark minúsculo. *Não* fez o mesmo para o `Proxy`. Com corpos de método maiores, ou com um inlining que falhe, as variantes com lambda e `Callable` podem começar a alocar também. O código gerado que nunca os cria não depende dessa sorte. **[V para a observação, I para a generalização]**
6. Ressalvas: é uma única máquina, uma guarda trivial, uma única thread, e o nível de polimorfismo é 4 (uma aplicação real pode ser mais polimórfica). Contenção e internos das políticas são assunto do agente de concorrência. A fase 2 deve repetir isto em x86 e com implementações reais das políticas.

---

## 4. Matriz de comparação :id=_4-comparison-matrix

| | Geração de código-fonte por APT | `Proxy` | ByteBuddy em runtime | Agente / LTW | AspectJ CTW |
|---|---|---|---|---|---|
| Overhead por chamada | ★★★★★ | ★★ | ★★★★★ | ★★★★ | ★★★★ (I) |
| Alocação por chamada | nenhuma | 16–64 B | nenhuma* | nenhuma* (I) | possíveis closures (I) |
| Inicialização | ★★★★★ (0,4 ms) | ★★★★ (2,4 ms) | ★ (200 ms) | ★ (I) | ★★★★★ |
| native-image | ★★★★★ | ★★★ (config) | ★ | ✗ | ★★★★ |
| Depurabilidade | ★★★★★ (código-fonte real) | ★★ | ★★ | ★★ | ★★★ |
| Complexidade de build do usuário | 1 linha de dependência | nenhuma | nenhuma (+ jar de 9 MB) | flag da JVM em todo lugar | plugin de compilador, atraso em relação ao JDK |
| Classes final / métodos privados | ✗ (subclasse) / OK (delegate sobre interface) | só interfaces | ✗ | ✓ | ✓ |
| Classes de terceiros | via interface + delegate | interfaces | ✗ | ✓ | ✓ (weaving binário) |
| Validação em tempo de compilação | ✓ | ✗ | ✗ | ✗ | parcial |
| Síncrono / CF / VT | especialização estática | verificações em runtime | verificações em runtime | verificações em runtime | verificações em runtime (I) |

\*No nosso benchmark, graças à escape analysis.

---

## 5. Recomendação ordenada :id=_5-ranked-recommendation

1. **Geração de código-fonte por APT (híbrido A)**: o mecanismo padrão e único no `core`, mais um módulo de processador separado. Para interfaces, gerar wrappers delegadores; para classes não final, gerar subclasses. O código gerado chama um **protocolo de política por método, sem alocação**, não um executor de lambdas compartilhado. O processador é *isolating*, e as instâncias são encontradas por convenção de nomes via `Resilient.of(...)`.
2. **API funcional / programática** no `core` (não é um mecanismo de anotação, mas a saída de emergência para código que não pode ser anotado, como clientes de terceiros ou lambdas).
3. **Módulo opcional de alternativa com `Proxy` (híbrido B)**: apenas opcional e apenas para interfaces, com um aviso no log que diga qual é o overhead.
4. **Plugin ByteBuddy em tempo de build (híbrido D)**: possível mais tarde para decorar no lugar classes final ou métodos privados.
5. **ByteBuddy em runtime**: não recomendado (inicialização, tamanho, native-image).
6. **AspectJ CTW / agente Java / LTW**: não recomendados (cerimônia para o usuário, atraso em relação ao JDK, direção do JEP 451).

**Implicações propostas para os módulos (para o agente de arquitetura de módulos).** Seguem a regra de nomes de uma só palavra: `core` (políticas, registry, protocolo, fachada `Resilient`), `processor` (APT, só em tempo de compilação), `annotations` (poderia ser fundido ao `core`; questão em aberto), `dynamic` (alternativa opcional com Proxy), `benchmarks` (JMH).

---

## 6. Esboço: como seria o código do usuário :id=_6-sketch-what-user-code-would-look-like

> Apenas ilustrativo. Nomes de anotações, nomes de atributos e a precedência da configuração cabem ao agente de design de API/configuração. Este esboço só mostra como o **mecanismo** se comporta na prática.

**Build (Gradle Kotlin DSL):**

```kotlin
dependencies {
    implementation("io.github.resilience4cata:core:0.1.0")
    annotationProcessor("io.github.resilience4cata:processor:0.1.0")
}
```

**Código do usuário:**

```java
public interface PaymentClient {

    @CircuitBreaker("payments")                 // named config "payments"
    @Retry(value = "payments", maxAttempts = 5) // named config + per-method override
    @Fallback("chargeOffline")                  // checked at compile time
    Receipt charge(Order order);

    @TimeLimiter("slow-calls")
    @Bulkhead("payments")
    CompletableFuture<Status> status(String id); // async path generated statically

    default Receipt chargeOffline(Order order, Throwable cause) {
        return Receipt.deferred(order);
    }
}

public final class HttpPaymentClient implements PaymentClient { /* plain code */ }
```

**Configuração e obtenção da instância:**

```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .defaults(d -> d.circuitBreaker(cb -> cb.failureRateThreshold(50)))
        .circuitBreaker("payments", cb -> cb.slidingWindowSize(100))
        .retry("payments", r -> r.maxAttempts(3).backoff(Duration.ofMillis(200)))
        .timeLimiter("slow-calls", t -> t.timeout(Duration.ofSeconds(2)))
        .load(ConfigSource.properties("resilience.properties")) // external config
        .build();

// Recommended entry point: one lookup now, zero reflection per call.
PaymentClient client = Resilient.of(PaymentClient.class, new HttpPaymentClient(), registry);

// Or, with no lookup at all, the generated factory:
PaymentClient client2 = PaymentClientResilience.wrap(new HttpPaymentClient(), registry);
```

**Para uma classe concreta (não final), o estilo subclasse:**

```java
public class InventoryService {
    @Retry("inventory")
    public int reserve(String sku, int qty) { ... }
}

InventoryService svc = Resilient.create(InventoryService.class, registry); // calls the generated subclass constructor
```

**O que o processador geraria (só a forma):**

```java
// build/generated/sources/annotationProcessor/java/main/.../PaymentClient$Resilient.java
@Generated("resilience4cata")
final class PaymentClient$Resilient implements PaymentClient {
    private final PaymentClient delegate;
    private final CircuitBreakerPolicy charge$cb;   // resolved ONCE from registry at wrap time
    private final RetryPolicy charge$retry;

    PaymentClient$Resilient(PaymentClient delegate, ResilienceRegistry r) {
        this.delegate = delegate;
        this.charge$cb = r.circuitBreaker("payments");
        this.charge$retry = r.retry("payments").withMaxAttempts(5);
    }

    @Override
    public Receipt charge(Order order) {
        int attempt = 0;
        while (true) {
            long permit = charge$cb.tryAcquire();            // no lambda, no boxing
            if (permit == CircuitBreakerPolicy.REJECTED) {
                return delegate.chargeOffline(order, charge$cb.rejection());
            }
            try {
                Receipt r = delegate.charge(order);          // monomorphic call site
                charge$cb.onSuccess(permit);
                return r;
            } catch (Throwable t) {
                charge$cb.onError(permit, t);
                if (!charge$retry.shouldRetry(++attempt, t)) {
                    return delegate.chargeOffline(order, t);
                }
                charge$retry.pause(attempt);                 // fine on virtual threads
            }
        }
    }
    // status(...) generated with CompletionStage callbacks instead of a loop
}
```

**Como as configurações nomeadas são referenciadas:** por nome, como string na anotação (`"payments"`). O processador valida a sintaxe e pode, opcionalmente, conferir os nomes com uma lista em tempo de compilação, como uma classe de constantes `@ResilienceConfigs`. Os nomes são **resolvidos contra o registry uma única vez, quando `Resilient.of` é executado**, e guardados em campos `final`. Atributos da anotação como `maxAttempts = 5` são constantes de tempo de compilação embutidas no código gerado como sobrescritas. Um nome ausente falha no momento de envolver, não na primeira chamada.

---

## 7. Questões em aberto para a fase 2 :id=_7-open-questions-for-phase-2

1. Interceptamos a **autoinvocação** no estilo subclasse, como o Micronaut faz por padrão? É mais poderoso, mas surpreende quem vem do Spring/Resilience4j.
2. Os fallbacks devem ficar na interface (métodos `default`) ou em uma classe `@FallbackFor` separada?
3. As políticas podem ser `static final` quando a configuração é totalmente conhecida em tempo de compilação? O ponto 3 de [§3](#3-our-own-measurements-jmh) sugere um ganho mensurável.
4. Como suportamos configuração recarregável a quente sem uma leitura volatile em cada chamada? (Coordenar com o agente de concorrência.)
5. Como plugamos tipos de retorno: tipos reativos, `Future`, tipos do usuário? Uma SPI resolvida **em tempo de compilação** pelo processador seria o ideal.
6. `annotations` e `core` devem ser módulos separados? Os usuários do processador precisam das anotações no classpath de compilação, e o código gerado precisa do core em runtime.

---

## Apêndice A: harness de benchmark (resumido) :id=appendix-a-benchmark-harness-condensed

```java
interface Service { int compute(int x); }

final class Guard {                       // minimal stand-in for a policy
    volatile boolean open; int errors;
    boolean permit() { return !open; }
    void onError() { errors++; }
    <T> T execute(Supplier<T> s) {        // Resilience4j-style functional decoration
        if (!permit()) throw new IllegalStateException();
        try { return s.get(); } catch (RuntimeException e) { onError(); throw e; }
    }
}

// "APT, protocol inlined": what a processor would emit
final class GeneratedWrapper implements Service {
    final Service delegate; final Guard guard;
    public int compute(int x) {
        if (!guard.permit()) throw new IllegalStateException();
        try { return delegate.compute(x); } catch (RuntimeException e) { guard.onError(); throw e; }
    }
}

// "APT, lambda": generated code reusing a shared functional executor
final class LambdaWrapper implements Service {
    final Service delegate; final Guard guard;
    public int compute(int x) { return guard.execute(() -> delegate.compute(x)); }
}

// Proxy + Method.invoke
InvocationHandler h = (p, m, a) -> {
    if (!g.permit()) throw new IllegalStateException();
    try { return m.invoke(target, a); }
    catch (InvocationTargetException e) { g.onError(); throw e.getCause(); }
};

// ByteBuddy Advice (advice code is copied into the generated method)
new ByteBuddy().subclass(ImplA.class).method(named("compute"))
    .intercept(Advice.to(GuardAdvice.class).wrap(SuperMethodCall.INSTANCE))
    .make().load(ImplA.class.getClassLoader()).getLoaded();
```

---

## Fontes :id=sources

- Gradle: [Processamento incremental de anotações](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing)
- OpenJDK: [JEP 416, Reimplement Core Reflection with Method Handles](https://openjdk.org/jeps/416) · [JEP 371, Hidden Classes](https://openjdk.org/jeps/371) · [JEP 451, Prepare to Disallow the Dynamic Loading of Agents](https://openjdk.org/jeps/451) · JEP 484 Class-File API: [e-mail de targeting](https://mail.openjdk.org/pipermail/jdk-dev/2024-October/009461.html), [commit da implementação](https://gitea.hb.dhbw-stuttgart.de/stan/jdk-24/commit/84ffb64cd73f8af11cf3670c6f19d282c2ac6961), [Inside Java Newscast #81](https://nipafx.dev/inside-java-newscast-81/)
- Padrão de processamento de anotações do JDK 23: [Notas de versão](https://www.oracle.com/java/technologies/javase/23-relnote-issues.html) · [Revisão do JDK-8321314](https://mail.openjdk.org/pipermail/compiler-dev/2024-May/026675.html) · [Thread da proposta](https://mail.openjdk.org/pipermail/jdk-dev/2024-May/009028.html)
- GraalVM: [Dynamic Proxy no Native Image](https://www.graalvm.org/jdk17/reference-manual/native-image/dynamic-features/DynamicProxy/) · [Metadados de alcançabilidade](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)
- Avaje Inject: [Aspects](https://avaje.io/inject/#aspects)
- Micronaut: [javadoc de `@Around`](https://docs.micronaut.io/4.8.2/api/io/micronaut/aop/Around.html) · [Guia de AOP](https://docs.micronaut.io/latest/guide/#aop)
- Byte Buddy: [README / tabela de versões](https://github.com/raphw/byte-buddy) · [Plugin Gradle](https://plugins.gradle.org/plugin/net.bytebuddy.byte-buddy-gradle-plugin)
- AspectJ: [1.9.23](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.23.html) · [1.9.22](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.22.html) · [1.9.21](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.21.html) · [thread do fórum sobre o plugin freefair](https://discuss.gradle.org/t/how-to-use-the-io-freefair-aspectj-post-compile-weaving/27954)
- JavaPoet: [palantir/javapoet](https://github.com/palantir/javapoet) · [Descontinuação do JavaPoet da Square (espelho)](https://docsearch.algolia.com/mcp/docs/repo/square/javapoet)
- Benchmarks de reflexão / MethodHandle / LambdaMetafactory: [Timefold: Java reflection, but much faster](https://timefold.ai/blog/java-reflection-but-much-faster) · [mlvm-dev: LambdaMetafactory vs MethodHandle](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006826.html) · [continuação](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006827.html) · [neoremind/dynamic-proxy (antigo, sem JMH)](https://github.com/neoremind/dynamic-proxy)
- Configuração das anotações do Resilience4j (dependência do Spring AOP): [Baeldung](https://www.baeldung.com/spring-boot-resilience4j) · [guia da oneuptime](https://oneuptime.com/blog/post/2026-01-25-circuit-breakers-resilience4j-spring/markdown) · [repositório do resilience4j](https://github.com/resilience4j/resilience4j)
