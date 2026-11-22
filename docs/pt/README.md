<div class="r4c-home">
<section class="hero" aria-labelledby="r4c-hero-title">
  <div class="wrap">
    <div class="hero-top">
      <div>
        <h1 id="r4c-hero-title">Resiliência,<br>compilada.</h1>
        <p class="sub">Anote uma interface Java com retry, circuit breaker, timeout e companhia. Em tempo de compilação, um processador de anotações escreve o Java simples que os aplica. Sem proxies, sem reflection, sem framework.</p>
        <div class="ctas">
          <a class="btn btn-primary" href="/pt/start/quickstart">Começar</a>
          <a class="btn btn-quiet" href="/pt/?id=benchmarks">Ver os benchmarks</a>
        </div>
        <ul class="facts">
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Java 21+</li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Funciona sem Spring, CDI ou AspectJ</li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Sync, <code>CompletionStage</code>, <code>Mono</code> e <code>Flux</code></li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Apache 2.0</li>
        </ul>
      </div>
      <div>
        <div class="r4c-install">
          <div class="r4c-install-head">
            <div role="tablist" aria-label="Ferramenta de build">
              <button role="tab" id="r4c-t-g" data-tab="gradle" aria-controls="r4c-p-g" aria-selected="true">Gradle</button>
              <button role="tab" id="r4c-t-k" data-tab="gradle (kotlin)" aria-controls="r4c-p-k" aria-selected="false" tabindex="-1">Gradle (Kotlin)</button>
              <button role="tab" id="r4c-t-m" data-tab="maven" aria-controls="r4c-p-m" aria-selected="false" tabindex="-1">Maven</button>
            </div>
            <button class="r4c-copy" type="button">Copiar</button>
          </div>
          <div role="tabpanel" id="r4c-p-g" aria-labelledby="r4c-t-g"><pre><span class="k">dependencies</span> {
    <span class="k">implementation</span> <span class="s">'dev.resilience4cata:resilience4cata-all:1.0.0-RC4'</span>
    <span class="k">annotationProcessor</span> <span class="s">'dev.resilience4cata:resilience4cata-processor:1.0.0-RC4'</span>
}</pre></div>
          <div role="tabpanel" id="r4c-p-k" aria-labelledby="r4c-t-k" hidden><pre><span class="k">dependencies</span> {
    implementation(<span class="s">"dev.resilience4cata:resilience4cata-all:1.0.0-RC4"</span>)
    annotationProcessor(<span class="s">"dev.resilience4cata:resilience4cata-processor:1.0.0-RC4"</span>)
}</pre></div>
          <div role="tabpanel" id="r4c-p-m" aria-labelledby="r4c-t-m" hidden><pre>&lt;<span class="k">dependency</span>&gt;
  &lt;<span class="k">groupId</span>&gt;dev.resilience4cata&lt;/<span class="k">groupId</span>&gt;
  &lt;<span class="k">artifactId</span>&gt;resilience4cata-all&lt;/<span class="k">artifactId</span>&gt;
  &lt;<span class="k">version</span>&gt;1.0.0-RC4&lt;/<span class="k">version</span>&gt;
&lt;/<span class="k">dependency</span>&gt;
<span></span>
<span class="c">&lt;!-- in the maven-compiler-plugin &lt;configuration&gt; --&gt;</span>
&lt;<span class="k">annotationProcessorPaths</span>&gt;
  &lt;<span class="k">path</span>&gt;
    &lt;<span class="k">groupId</span>&gt;dev.resilience4cata&lt;/<span class="k">groupId</span>&gt;
    &lt;<span class="k">artifactId</span>&gt;resilience4cata-processor&lt;/<span class="k">artifactId</span>&gt;
    &lt;<span class="k">version</span>&gt;1.0.0-RC4&lt;/<span class="k">version</span>&gt;
  &lt;/<span class="k">path</span>&gt;
&lt;/<span class="k">annotationProcessorPaths</span>&gt;</pre></div>
        </div>
        <p class="install-note">Versão 1.0.0-RC4 no Maven Central: uma <a href="/pt/blog/release-candidate">release candidate</a>, com a API congelada para a 1.0. <code>resilience4cata-all</code> traz o core, as anotações e a configuração; o processador vai apenas no annotation processor path. <a href="/pt/start/installation">Guia de instalação</a></p>
      </div>
    </div>
    <div class="r4c-compile" aria-label="Interface anotada e a classe que o processador gera a partir dela">
      <div class="r4c-pane r4c-pane-src">
        <div class="r4c-pane-head"><strong>Você escreve</strong><code>PaymentClient.java</code></div>
<pre><span class="k">public interface</span> <span class="t">PaymentClient</span> {
<span></span>
    <span class="hot" tabindex="0" data-layer="retry"><span class="a">@Retry</span>(maxAttempts = <span class="n">3</span>, delay = <span class="s">"200ms"</span>)</span>
    <span class="hot" tabindex="0" data-layer="cb"><span class="a">@CircuitBreaker</span></span>
    <span class="hot" tabindex="0" data-layer="timeout"><span class="a">@Timeout</span>(<span class="s">"2s"</span>)</span>
    <span class="t">Receipt</span> charge(<span class="t">Order</span> order);
}
<span></span>
<span class="c">// once, at startup</span>
<span class="t">ResilienceRegistry</span> registry =
        <span class="t">ResilienceRegistry</span>.builder().build();
<span class="t">PaymentClient</span> client = <span class="t">Resilient</span>.of(
        <span class="t">PaymentClient</span>.<span class="k">class</span>, <span class="k">new</span> <span class="t">HttpPaymentClient</span>(), registry);
<span></span>
client.charge(order); <span class="c">// retried, guarded, timed out</span></pre>
      </div>
      <div class="r4c-gutter" aria-hidden="true">
        <svg viewBox="0 0 64 120" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 60h18"/><circle cx="24" cy="60" r="2.5" fill="currentColor"/><path d="M26 58 L40 46"/><circle cx="42" cy="60" r="2.5"/><path d="M45 60h15"/><path d="M54 54l6 6-6 6"/></svg>
        <span><b>javac</b><br>+ processador</span>
      </div>
      <div class="r4c-pane r4c-pane-gen">
        <div class="r4c-pane-head"><strong>O processador escreve</strong><code>PaymentClient$Resilient.java</code></div>
<pre><span class="c">// Generated by the resilience4cata annotation processor. Do not edit.</span>
<span class="k">public final class</span> <span class="t">PaymentClient$Resilient</span> <span class="k">implements</span> <span class="t">PaymentClient</span> {
<span></span>
    <span class="k">private final</span> <span class="t">PaymentClient</span> delegate;
    <span class="k">private final</span> <span class="t">RetryPolicy</span> retry0;
    <span class="k">private final</span> <span class="t">CircuitBreakerPolicy</span> circuitBreaker0;
    <span class="k">private final</span> <span class="t">TimeoutPolicy</span> timeout0;
    <span class="c">// … constructor: each policy resolved once from the registry</span>
<span></span>
<span class="blk" data-layer="retry">    <span class="a">@Override</span>
    <span class="k">public</span> <span class="t">Receipt</span> charge(<span class="t">Order</span> p0) {
        <span class="k">final</span> <span class="t">RetryPolicy</span> r4c$retry = <span class="k">this</span>.retry0;
        <span class="k">final long</span> r4c$start = r4c$retry.start();
        <span class="k">int</span> r4c$attempts = <span class="n">0</span>;
        <span class="k">while</span> (<span class="k">true</span>) {
            <span class="k">try</span> {
                <span class="k">return this</span>.r4c$circuitBreaker0(p0);
            } <span class="k">catch</span> (<span class="t">Throwable</span> r4c$e) {
                <span class="k">final long</span> r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start);
                <span class="k">if</span> (r4c$delay &lt; <span class="n">0</span> || !r4c$retry.pause(r4c$delay)) {
                    <span class="k">throw</span> r4c$e;
                }
            }
        }
    }
</span><span class="blk" data-layer="cb">    <span class="k">private</span> <span class="t">Receipt</span> r4c$circuitBreaker0(<span class="t">Order</span> p0) {
        <span class="k">final</span> <span class="t">CircuitBreakerPolicy</span> r4c$cb = <span class="k">this</span>.circuitBreaker0;
        <span class="k">final long</span> r4c$token = r4c$cb.acquire();
        <span class="k">if</span> (r4c$token &lt; <span class="n">0</span>) {
            <span class="k">throw</span> r4c$cb.rejection();
        }
        <span class="k">final long</span> r4c$started = r4c$cb.startTimer();
        <span class="k">try</span> {
            <span class="k">final</span> <span class="t">Receipt</span> r4c$result = <span class="k">this</span>.r4c$timeout0(p0);
            r4c$cb.onSuccess(r4c$token, r4c$started);
            <span class="k">return</span> r4c$result;
        } <span class="k">catch</span> (<span class="t">Throwable</span> r4c$e) {
            r4c$cb.onFailure(r4c$token, r4c$started, r4c$e);
            <span class="k">throw</span> r4c$e;
        }
    }
</span><span class="blk" data-layer="timeout">    <span class="k">private</span> <span class="t">Receipt</span> r4c$timeout0(<span class="t">Order</span> p0) {
        <span class="k">final</span> <span class="t">TimeoutPolicy</span> r4c$timeout = <span class="k">this</span>.timeout0;
        <span class="k">final</span> <span class="t">TimeoutPolicy.Scope</span> r4c$scope = r4c$timeout.enter();
        <span class="k">final</span> <span class="t">Receipt</span> r4c$result;
        <span class="k">try</span> {
            r4c$result = <span class="k">this</span>.delegate.charge(p0);
        } <span class="k">catch</span> (<span class="t">Throwable</span> r4c$e) {
            r4c$timeout.failed(r4c$scope, r4c$e);
            <span class="k">throw</span> r4c$e;
        }
        r4c$timeout.succeeded(r4c$scope);
        <span class="k">return</span> r4c$result;
    }
</span>}</pre>
      </div>
      <div class="r4c-chain" role="group" aria-label="Destacar uma camada">
        <span>Cada chamada passa por</span>
        <button type="button" data-layer="retry" aria-pressed="false">Retry</button><span class="wire"></span>
        <button type="button" data-layer="cb" aria-pressed="false">CircuitBreaker</button><span class="wire"></span>
        <button type="button" data-layer="timeout" aria-pressed="false">Timeout</button><span class="wire"></span>
        <span><code>HttpPaymentClient.charge</code></span>
        <span class="note">Resumido: o arquivo real usa nomes totalmente qualificados.</span>
      </div>
    </div>
  </div>
</section>
<section aria-labelledby="why">
  <div class="wrap">
    <h2 class="h2" id="why">A proteção é código que você pode ler</h2>
    <p class="lead">Resilience4j e MicroProfile envolvem suas chamadas em decorators ou proxies em tempo de execução. resilience4cata faz esse trabalho no compilador, então o que roda é Java comum.</p>
    <div class="why-grid">
      <div class="why">
        <h3>Entre nele com um debugger</h3>
        <p>O processador escreve <code>PaymentClient$Resilient</code> ao lado das suas próprias classes. Cada política são poucas linhas diretas em um método que você pode abrir, onde pode pôr um breakpoint e que pode ler em um stack trace.</p>
<pre>at HttpPaymentClient.charge
at PaymentClient$Resilient.r4c$timeout0
at PaymentClient$Resilient.r4c$circuitBreaker0
at PaymentClient$Resilient.charge
at CheckoutService.pay</pre>
      </div>
      <div class="why">
        <h3>Nada alocado quando chamadas síncronas têm sucesso</h3>
        <p>Chamadas síncronas que têm sucesso ou são rejeitadas alocam zero bytes. Um gate de alocação roda os benchmarks em cada pull request e quebra o build se isso mudar. Chamadas async alocam seus futures, e a primeira chamada com timeout de uma thread a registra uma vez: veja <a href="/pt/performance">Desempenho</a>.</p>
        <div class="gate" role="img" aria-label="Orçamentos de alocação: sucesso do retry, sucesso do circuit breaker e rejeição do bulkhead são todos 0 bytes por operação">
          <div><span>Retry, sucesso</span><span class="ok">0 B/op</span></div>
          <div><span>CircuitBreaker, sucesso</span><span class="ok">0 B/op</span></div>
          <div><span>Bulkhead, rejeitada</span><span class="ok">0 B/op</span></div>
        </div>
      </div>
      <div class="why">
        <h3>Erros quebram o build</h3>
        <p>Uma duração inválida, um fallback com a assinatura errada ou uma anotação em um método final é um erro de compilação com um código estável e uma correção, não uma surpresa em produção.</p>
<pre><span class="err">error:</span> [R4C-ATR-001] @Retry.delay = "2 seconds"
  is not a valid duration: … Use a number
  followed by ns, us, ms, s, m, h or d …
    @Retry(delay = "2 seconds")
    ^</pre>
        <p style="margin-top:12px"><a href="/pt/errors?id=r4c-atr-001">Todos os códigos de erro, com correção</a></p>
      </div>
    </div>
  </div>
</section>
<section id="benchmarks" aria-labelledby="bench-h">
  <div class="wrap bench">
    <div class="bench-meta">
      <h2 class="h2" id="bench-h">Medido, sempre</h2>
      <p class="lead" style="font-size:17px">Cada número deste site vem de uma execução JMH no repositório, contra Resilience4j com configuração equivalente. Onde não ganhamos, os resultados completos dizem.</p>
      <dl>
        <dt>Máquina</dt><dd>AWS c7g.xlarge, 4 núcleos Graviton3</dd>
        <dt>JMH</dt><dd>6 forks × 5 × 1 s, <code>-prof gc</code></dd>
        <dt>Comparado com</dt><dd>Resilience4j 2.4.0</dd>
        <dt>Data</dt><dd>2026-11-02</dd>
      </dl>
      <p class="bench-score"><b>150 de 156</b> comparações ganhas na <a href="/pt/benchmarks?id=frameworks">execução completa da 0.13.0</a>. As outras seis estão listadas lá, com o porquê.</p>
      <a href="/pt/benchmarks?id=release-candidate">Métodos, todos os resultados e notas de equidade</a>
    </div>
    <figure class="r4c-chart">
      <figcaption class="legend">
        <span><i style="background:var(--series-r4c)"></i>resilience4cata 1.0.0-RC1</span>
        <span><i style="background:var(--series-r4j)"></i>Resilience4j 2.4.0</span>
        <span class="threads" role="radiogroup" aria-label="Threads"><label><input type="radio" name="bench-threads" value="1" checked>1 thread</label><label><input type="radio" name="bench-threads" value="4">4 threads</label></span>
      </figcaption>
      <div class="rows t1">
        <div class="row"><div class="row-label">Retry, a primeira tentativa tem sucesso</div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 1.4 ns por chamada, 0 B alocados"><i style="width:2.9%"></i><b>1.4 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 22.9 ns por chamada, 0 B alocados"><i style="width:27.2%"></i><b>22.9 ns<span class="bytes"> · 0 B</span></b></span></div>
          <div class="ratio"><b>16×</b><small>mais rápido</small></div></div>
        <div class="row"><div class="row-label">Circuit breaker, toda chamada tem sucesso<small>Resilience4j no modo SYNCHRONIZED</small></div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 1.9 ns por chamada, 0 B alocados"><i style="width:5.6%"></i><b>1.9 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 89.9 ns por chamada, 0 B alocados"><i style="width:39.1%"></i><b>89.9 ns<span class="bytes"> · 0 B</span></b></span></div>
          <div class="ratio"><b>47×</b><small>mais rápido</small></div></div>
        <div class="row"><div class="row-label">Timeout síncrono, a chamada termina a tempo<small>Resilience4j: TimeLimiter em um executor</small></div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 23.6 ns por chamada, 0 B alocados"><i style="width:27.5%"></i><b>23.6 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j TimeLimiter em um executor: 8907.6 ns por chamada, 183 B alocados"><i style="width:79.0%"></i><b>8&nbsp;908 ns<span class="bytes"> · 183 B</span></b></span></div>
          <div class="ratio"><b>377×</b><small>mais rápido</small></div></div>
        <div class="row"><div class="row-label">Bulkhead com vagas livres</div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 17.3 ns por chamada, 0 B alocados"><i style="width:24.8%"></i><b>17.3 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 20.1 ns por chamada, 0 B alocados"><i style="width:26.1%"></i><b>20.1 ns<span class="bytes"> · 0 B</span></b></span></div>
          <div class="ratio"><b>1.2×</b><small>mais rápido</small></div></div>
      </div>
      <div class="rows t4">
        <div class="row"><div class="row-label">Retry, a primeira tentativa tem sucesso</div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 1.3 ns por chamada, 0 B alocados"><i style="width:2.3%"></i><b>1.3 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 23.0 ns por chamada, 0 B alocados"><i style="width:27.2%"></i><b>23.0 ns<span class="bytes"> · 0 B</span></b></span></div>
          <div class="ratio"><b>18×</b><small>mais rápido</small></div></div>
        <div class="row"><div class="row-label">Circuit breaker, toda chamada tem sucesso<small>um breaker compartilhado por quatro threads</small></div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 1.9 ns por chamada, 0 B alocados"><i style="width:5.6%"></i><b>1.9 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 849.5 ns por chamada, 2 B alocados"><i style="width:58.6%"></i><b>850 ns<span class="bytes"> · 2 B</span></b></span></div>
          <div class="ratio"><b>447×</b><small>mais rápido</small></div></div>
        <div class="row"><div class="row-label">Timeout síncrono, a chamada termina a tempo<small>Resilience4j: TimeLimiter em um executor</small></div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 23.6 ns por chamada, 0 B alocados"><i style="width:27.5%"></i><b>23.6 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j TimeLimiter em um executor: 9976.8 ns por chamada, 151 B alocados"><i style="width:80.0%"></i><b>9&nbsp;977 ns<span class="bytes"> · 151 B</span></b></span></div>
          <div class="ratio"><b>423×</b><small>mais rápido</small></div></div>
        <div class="row"><div class="row-label">Bulkhead com vagas livres<small>um bulkhead compartilhado por quatro threads</small></div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 192.3 ns por chamada, 0 B alocados"><i style="width:45.7%"></i><b>192 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 410.8 ns por chamada, 0 B alocados"><i style="width:52.3%"></i><b>411 ns<span class="bytes"> · 0 B</span></b></span></div>
          <div class="ratio"><b>2.1×</b><small>mais rápido</small></div></div>
      </div>
      <div class="axis"><span></span><div><span style="left:0%">1 ns</span><span style="left:20%">10 ns</span><span style="left:40%">100 ns</span><span style="left:60%">1 µs</span><span style="left:80%">10 µs</span><span style="left:100%">100 µs</span></div><span></span></div>
      <p class="axis-note">Tempo por chamada, escala logarítmica; a razão à direita é a média do Resilience4j dividida pela nossa. Menor é melhor. Médias medidas apenas em ARM (Graviton3). As linhas do breaker são o caso em que toda chamada tem sucesso; quando chamadas falham a diferença diminui (3.4× com quatro threads quando uma chamada em dez falha), veja <a href="/pt/performance">Desempenho</a>.</p>
      <details><summary>Mostrar como tabela</summary>
        <div class="tablewrap"><table>
          <thead><tr><th>Cenário</th><th class="num">Threads</th><th class="num">resilience4cata</th><th class="num">Resilience4j</th><th class="num">Razão</th></tr></thead>
          <tbody>
            <tr><td>Retry, a primeira tentativa tem sucesso</td><td class="num">1</td><td class="num">1.4 ns, 0 B</td><td class="num">22.9 ns, 0 B</td><td class="num">16×</td></tr>
            <tr><td>Retry, a primeira tentativa tem sucesso</td><td class="num">4</td><td class="num">1.3 ns, 0 B</td><td class="num">23.0 ns, 0 B</td><td class="num">18×</td></tr>
            <tr><td>Circuit breaker, toda chamada tem sucesso</td><td class="num">1</td><td class="num">1.9 ns, 0 B</td><td class="num">89.9 ns, 0 B</td><td class="num">47×</td></tr>
            <tr><td>Circuit breaker, toda chamada tem sucesso</td><td class="num">4</td><td class="num">1.9 ns, 0 B</td><td class="num">849.5 ns, 2 B</td><td class="num">447×</td></tr>
            <tr><td>Timeout síncrono, a chamada termina a tempo</td><td class="num">1</td><td class="num">23.6 ns, 0 B</td><td class="num">8&nbsp;907.6 ns, 183 B</td><td class="num">377×</td></tr>
            <tr><td>Timeout síncrono, a chamada termina a tempo</td><td class="num">4</td><td class="num">23.6 ns, 0 B</td><td class="num">9&nbsp;976.8 ns, 151 B</td><td class="num">423×</td></tr>
            <tr><td>Bulkhead com vagas livres</td><td class="num">1</td><td class="num">17.3 ns, 0 B</td><td class="num">20.1 ns, 0 B</td><td class="num">1.2×</td></tr>
            <tr><td>Bulkhead com vagas livres</td><td class="num">4</td><td class="num">192.3 ns, 0 B</td><td class="num">410.8 ns, 0 B</td><td class="num">2.1×</td></tr>
          </tbody></table></div>
      </details>
      <div class="r4c-chart-tip" hidden></div>
    </figure>
  </div>
</section>
<section id="policies" aria-labelledby="pol-h">
  <div class="wrap">
    <h2 class="h2" id="pol-h">Oito políticas, uma anotação cada</h2>
    <p class="lead">Coloque-as em um método ou na interface inteira. Combine-as livremente: o processador as aninha em uma ordem bem escolhida, que <code>@PolicyOrder</code> pode mudar.</p>
    <div class="panel">
      <a class="breaker" href="/pt/policies/retry"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 12a8 8 0 1 1-2.34-5.66"/><path d="M20 4.5V8.5H16"/><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/></svg></span><span><h3>@Retry</h3><p>Tenta de novo com backoff exponencial e jitter, dentro de um orçamento de tempo opcional.</p><span class="attrs">maxAttempts · delay · backoff · maxDuration</span></span></a>
      <a class="breaker" href="/pt/policies/hedge"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8h13"/><path d="M13 5l3 3-3 3"/><path d="M8 16h13"/><path d="M18 13l3 3-3 3"/></svg></span><span><h3>@Hedge</h3><p>Inicia de novo a mesma chamada async idempotente quando ela está lenta, e fica com o primeiro sucesso.</p><span class="attrs">delay · maxAttempts · hedgeOn · budgetPercent</span></span></a>
      <a class="breaker" href="/pt/policies/circuit-breaker"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 16h4.5"/><circle cx="8" cy="16" r="1.6"/><path d="M9.2 15 L17 7.5"/><circle cx="16.5" cy="16" r="1.6"/><path d="M18 16h4"/></svg></span><span><h3>@CircuitBreaker</h3><p>Para de chamar uma dependência que continua falhando, depois deixa passar algumas chamadas de teste.</p><span class="attrs">failureRateThreshold · waitInOpen · halfOpenCalls</span></span></a>
      <a class="breaker" href="/pt/policies/rate-limiter"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 4h16l-5.5 6h-5z"/><path d="M12 10v1.5"/><circle cx="12" cy="15" r="1.3" fill="currentColor" stroke="none"/><circle cx="12" cy="20" r="1.3" fill="currentColor" stroke="none"/></svg></span><span><h3>@RateLimiter</h3><p>Admite um número fixo de chamadas por período, rejeitando ou esperando pelas demais.</p><span class="attrs">permits · period · maxWait</span></span></a>
      <a class="breaker" href="/pt/policies/adaptive-limit"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 17a8 8 0 1 1 16 0"/><path d="M12 17l4-5"/><circle cx="12" cy="17" r="1.4" fill="currentColor" stroke="none"/></svg></span><span><h3>@AdaptiveLimit</h3><p>Limita as chamadas concorrentes com um limite que se encontra sozinho: sobe enquanto a latência se mantém, desce quando ela aumenta.</p><span class="attrs">algorithm · minLimit · maxLimit</span></span></a>
      <a class="breaker" href="/pt/policies/timeout"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="13.5" r="7"/><path d="M12 13.5V9.5"/><path d="M10 3h4"/><path d="M12 3v3.5"/><path d="M18.2 6.8l1.4-1.4"/></svg></span><span><h3>@Timeout</h3><p>Interrompe uma chamada que passa do seu orçamento, na própria thread de quem chama.</p><span class="attrs">value</span></span></a>
      <a class="breaker" href="/pt/policies/bulkhead"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8h18l-1.8 8.4a2 2 0 0 1-2 1.6H6.8a2 2 0 0 1-2-1.6z"/><path d="M9 8v10"/><path d="M15 8v10"/><path d="M12 8V4.5"/></svg></span><span><h3>@Bulkhead</h3><p>Limita quantas chamadas rodam ao mesmo tempo, para que uma dependência lenta não tome todas as threads.</p><span class="attrs">maxConcurrent · maxWait</span></span></a>
      <a class="breaker" href="/pt/policies/fallback"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7h10"/><path d="M15.5 5.5l3 3"/><path d="M18.5 5.5l-3 3"/><path d="M7.5 7c0 6 3 10 8.5 10H21"/><path d="M18 14l3 3-3 3"/></svg></span><span><h3>@Fallback</h3><p>Devolve uma resposta útil quando todo o resto falhou, a partir de um método irmão ou de uma classe handler.</p><span class="attrs">value · method</span></span></a>
    </div>
    <p class="order">Ordem, da mais externa para dentro: Fallback, Retry ou Hedge, CircuitBreaker, RateLimiter, AdaptiveLimit, Timeout, Bulkhead, e então o seu método. <a href="/pt/policies/">Como se combinam</a></p>
  </div>
</section>
<section aria-labelledby="qs-h">
  <div class="wrap">
    <h2 class="h2" id="qs-h">Da dependência à primeira chamada protegida</h2>
    <ol class="steps">
      <li><h3>Adicione o processador</h3><p>Core em tempo de execução, anotações só na compilação, o processador no annotation processor path.</p>
<pre><span class="k">annotationProcessor</span> <span class="s">'dev.resilience4cata:</span>
<span class="s">    resilience4cata-processor'</span></pre></li>
      <li><h3>Anote uma interface</h3><p>Durações são strings como <code>"200ms"</code>, verificadas ao compilar.</p>
<pre><span class="a">@Retry</span>(maxAttempts = <span class="n">3</span>)
<span class="a">@Timeout</span>(<span class="s">"2s"</span>)
<span class="t">Receipt</span> charge(<span class="t">Order</span> order);</pre></li>
      <li><h3>Envolva sua implementação</h3><p>Um registry por aplicação compartilha o estado de breakers e limiters por nome.</p>
<pre><span class="t">Resilient</span>.of(<span class="t">PaymentClient</span>.<span class="k">class</span>,
    <span class="k">new</span> <span class="t">HttpPaymentClient</span>(), registry);</pre></li>
    </ol>
    <div class="cta-row">
      <a class="btn btn-primary" href="/pt/start/quickstart">Seguir o início rápido</a>
      <a class="btn btn-quiet" href="/pt/start/how-it-works">Como funciona</a>
    </div>
  </div>
</section>
<div class="foot-wrap">
  <div class="wrap foot">
    <div>
      <a class="r4c-brand" href="/pt/"><svg viewBox="0 0 40 40" aria-hidden="true"><rect width="40" height="40" rx="9" fill="#F2B500"/><g fill="none" stroke="#1A1F24" stroke-width="3.2" stroke-linecap="round"><path d="M4 27h7"/><path d="M13.6 25.4 L27 13"/><path d="M31 27h5"/></g><circle cx="13" cy="27" r="2.6" fill="#1A1F24"/><circle cx="29" cy="27" r="2.6" fill="none" stroke="#1A1F24" stroke-width="2.4"/></svg><span>resilience<b>4cata</b></span></a>
      <p>Resiliência baseada em anotações para Java 21+ puro. Licença Apache 2.0.</p>
      <span class="coord">dev.resilience4cata:resilience4cata-bom:1.0.0-RC4</span>
    </div>
    <div><h4>Aprender</h4><ul><li><a href="/pt/start/installation">Instalação</a></li><li><a href="/pt/start/quickstart">Início rápido</a></li><li><a href="/pt/start/how-it-works">Como funciona</a></li><li><a href="/pt/guides/testing">Testes com ManualClock</a></li></ul></div>
    <div><h4>Referência</h4><ul><li><a href="/pt/errors">Códigos de erro</a></li><li><a href="/pt/reference/configuration">Chaves de configuração</a></li><li><a href="/pt/performance">Desempenho</a></li><li><a href="https://javadoc.io/doc/dev.resilience4cata">Javadoc</a></li></ul></div>
    <div><h4>Projeto</h4><ul><li><a href="https://github.com/fabiangftech/resilience4cata">GitHub</a></li><li><a href="https://central.sonatype.com/namespace/dev.resilience4cata">Maven Central</a></li><li><a href="https://github.com/fabiangftech/resilience4cata/releases">Versões</a></li><li><a href="https://www.apache.org/licenses/LICENSE-2.0">Licença Apache 2.0</a></li><li><a href="/en/" lang="en" hreflang="en">Documentation in English</a></li><li><a href="/es/" lang="es" hreflang="es">Documentación en español</a></li><li><a href="/zh/" lang="zh-CN" hreflang="zh-CN">中文文档</a></li></ul></div>
  </div>
</div>
</div>
