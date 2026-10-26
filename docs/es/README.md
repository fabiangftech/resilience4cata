<div class="r4c-home">
<section class="hero" aria-labelledby="r4c-hero-title">
  <div class="wrap">
    <div class="hero-top">
      <div>
        <h1 id="r4c-hero-title">Resiliencia,<br>compilada.</h1>
        <p class="sub">Anota una interfaz Java con retry, circuit breaker, timeout y compañía. Al compilar, un procesador de anotaciones escribe el Java plano que los aplica. Sin proxies, sin reflexión, sin framework.</p>
        <div class="ctas">
          <a class="btn btn-primary" href="#/es/start/quickstart">Empezar</a>
          <a class="btn btn-quiet" href="#/es/?id=benchmarks">Ver los benchmarks</a>
        </div>
        <ul class="facts">
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Java 21+</li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Funciona sin Spring, CDI ni AspectJ</li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Síncrono y <code>CompletionStage</code></li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Apache 2.0</li>
        </ul>
      </div>
      <div>
        <div class="r4c-install">
          <div class="r4c-install-head">
            <div role="tablist" aria-label="Herramienta de build">
              <button role="tab" id="r4c-t-g" data-tab="gradle" aria-controls="r4c-p-g" aria-selected="true">Gradle</button>
              <button role="tab" id="r4c-t-k" data-tab="gradle (kotlin)" aria-controls="r4c-p-k" aria-selected="false" tabindex="-1">Gradle (Kotlin)</button>
              <button role="tab" id="r4c-t-m" data-tab="maven" aria-controls="r4c-p-m" aria-selected="false" tabindex="-1">Maven</button>
            </div>
            <button class="r4c-copy" type="button">Copiar</button>
          </div>
          <div role="tabpanel" id="r4c-p-g" aria-labelledby="r4c-t-g"><pre><span class="k">dependencies</span> {
    <span class="k">implementation</span> platform(<span class="s">'dev.resilience4cata:resilience4cata-bom:0.6.0'</span>)
    <span class="k">annotationProcessor</span> platform(<span class="s">'dev.resilience4cata:resilience4cata-bom:0.6.0'</span>)
    <span class="k">implementation</span> <span class="s">'dev.resilience4cata:resilience4cata-core'</span>
    <span class="k">compileOnly</span> <span class="s">'dev.resilience4cata:resilience4cata-annotations'</span>
    <span class="k">annotationProcessor</span> <span class="s">'dev.resilience4cata:resilience4cata-processor'</span>
}</pre></div>
          <div role="tabpanel" id="r4c-p-k" aria-labelledby="r4c-t-k" hidden><pre><span class="k">dependencies</span> {
    <span class="k">val</span> r4cBom = platform(<span class="s">"dev.resilience4cata:resilience4cata-bom:0.6.0"</span>)
    implementation(r4cBom)
    annotationProcessor(r4cBom)
    implementation(<span class="s">"dev.resilience4cata:resilience4cata-core"</span>)
    compileOnly(<span class="s">"dev.resilience4cata:resilience4cata-annotations"</span>)
    annotationProcessor(<span class="s">"dev.resilience4cata:resilience4cata-processor"</span>)
}</pre></div>
          <div role="tabpanel" id="r4c-p-m" aria-labelledby="r4c-t-m" hidden><pre>&lt;<span class="k">dependencies</span>&gt;
  &lt;<span class="k">dependency</span>&gt;
    &lt;<span class="k">groupId</span>&gt;dev.resilience4cata&lt;/<span class="k">groupId</span>&gt;
    &lt;<span class="k">artifactId</span>&gt;resilience4cata-core&lt;/<span class="k">artifactId</span>&gt;
    &lt;<span class="k">version</span>&gt;0.6.0&lt;/<span class="k">version</span>&gt;
  &lt;/<span class="k">dependency</span>&gt;
  &lt;<span class="k">dependency</span>&gt;
    &lt;<span class="k">groupId</span>&gt;dev.resilience4cata&lt;/<span class="k">groupId</span>&gt;
    &lt;<span class="k">artifactId</span>&gt;resilience4cata-annotations&lt;/<span class="k">artifactId</span>&gt;
    &lt;<span class="k">version</span>&gt;0.6.0&lt;/<span class="k">version</span>&gt;
    &lt;<span class="k">scope</span>&gt;provided&lt;/<span class="k">scope</span>&gt;
  &lt;/<span class="k">dependency</span>&gt;
&lt;/<span class="k">dependencies</span>&gt;
<span></span>
<span class="c">&lt;!-- in the maven-compiler-plugin &lt;configuration&gt; --&gt;</span>
&lt;<span class="k">annotationProcessorPaths</span>&gt;
  &lt;<span class="k">path</span>&gt;
    &lt;<span class="k">groupId</span>&gt;dev.resilience4cata&lt;/<span class="k">groupId</span>&gt;
    &lt;<span class="k">artifactId</span>&gt;resilience4cata-processor&lt;/<span class="k">artifactId</span>&gt;
    &lt;<span class="k">version</span>&gt;0.6.0&lt;/<span class="k">version</span>&gt;
  &lt;/<span class="k">path</span>&gt;
&lt;/<span class="k">annotationProcessorPaths</span>&gt;</pre></div>
        </div>
        <p class="install-note">Versión 0.6.0 en Maven Central. El procesador va solo en el path de procesadores de anotaciones; las anotaciones solo hacen falta al compilar. <a href="#/es/start/installation">Guía de instalación</a></p>
      </div>
    </div>
    <div class="r4c-compile" aria-label="Interfaz anotada y la clase que el procesador genera a partir de ella">
      <div class="r4c-pane r4c-pane-src">
        <div class="r4c-pane-head"><strong>Tú escribes</strong><code>PaymentClient.java</code></div>
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
        <span><b>javac</b><br>+ procesador</span>
      </div>
      <div class="r4c-pane r4c-pane-gen">
        <div class="r4c-pane-head"><strong>El procesador escribe</strong><code>PaymentClient$Resilient.java</code></div>
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
      <div class="r4c-chain" role="group" aria-label="Resaltar una capa">
        <span>Cada llamada pasa por</span>
        <button type="button" data-layer="retry" aria-pressed="false">Retry</button><span class="wire"></span>
        <button type="button" data-layer="cb" aria-pressed="false">CircuitBreaker</button><span class="wire"></span>
        <button type="button" data-layer="timeout" aria-pressed="false">Timeout</button><span class="wire"></span>
        <span><code>HttpPaymentClient.charge</code></span>
        <span class="note">Abreviado: el archivo real usa nombres completamente calificados.</span>
      </div>
    </div>
  </div>
</section>
<section aria-labelledby="why">
  <div class="wrap">
    <h2 class="h2" id="why">La protección es código que puedes leer</h2>
    <p class="lead">Resilience4j y MicroProfile envuelven tus llamadas en decoradores o proxies en tiempo de ejecución. resilience4cata hace ese trabajo en el compilador, así que lo que se ejecuta es Java común y corriente.</p>
    <div class="why-grid">
      <div class="why">
        <h3>Entra con el debugger</h3>
        <p>El procesador escribe <code>PaymentClient$Resilient</code> junto a tus propias clases. Cada política son unas pocas líneas rectas en un método que puedes abrir, donde puedes poner un breakpoint y que lees en un stack trace.</p>
<pre>at HttpPaymentClient.charge
at PaymentClient$Resilient.r4c$timeout0
at PaymentClient$Resilient.r4c$circuitBreaker0
at PaymentClient$Resilient.charge
at CheckoutService.pay</pre>
      </div>
      <div class="why">
        <h3>Nada se asigna cuando las llamadas salen bien</h3>
        <p>Las llamadas síncronas que salen bien o se rechazan asignan cero bytes. Un gate de allocations ejecuta los benchmarks en cada pull request y hace fallar el build si eso cambia.</p>
        <div class="gate" role="img" aria-label="Presupuestos de allocations: retry exitoso, circuit breaker exitoso y bulkhead rechazado son 0 bytes por operación">
          <div><span>Retry, success</span><span class="ok">0 B/op</span></div>
          <div><span>CircuitBreaker, success</span><span class="ok">0 B/op</span></div>
          <div><span>Bulkhead, rejected</span><span class="ok">0 B/op</span></div>
        </div>
      </div>
      <div class="why">
        <h3>Los errores hacen fallar el build</h3>
        <p>Una duración mal escrita, un fallback con la firma equivocada o una anotación en un método final es un error de compilación con un código estable y una solución, no una sorpresa en producción.</p>
<pre><span class="err">error:</span> [R4C-ATR-001] @Retry.delay = "2 seconds"
  is not a valid duration: … Use a number
  followed by ns, us, ms, s, m, h or d …
    @Retry(delay = "2 seconds")
    ^</pre>
        <p style="margin-top:12px"><a href="#/es/errors?id=r4c-atr-001">Cada código de error, con su solución</a></p>
      </div>
    </div>
  </div>
</section>
<section id="benchmarks" aria-labelledby="bench-h">
  <div class="wrap bench">
    <div class="bench-meta">
      <h2 class="h2" id="bench-h">Medido, siempre</h2>
      <p class="lead" style="font-size:17px">Cada número de este sitio viene de una corrida de JMH en el repositorio, contra Resilience4j con una configuración equivalente. Donde no ganamos, la tabla lo dice.</p>
      <dl>
        <dt>Máquina</dt><dd>AWS c7g.xlarge, 4 núcleos Graviton3</dd>
        <dt>JMH</dt><dd>3 forks × 5 × 1 s, <code>-prof gc</code></dd>
        <dt>Comparado con</dt><dd>Resilience4j 2.4.0</dd>
        <dt>Fecha</dt><dd>2026-10-24</dd>
      </dl>
      <a href="#/es/benchmarks?id=cierre-del-mvp-classifier-pipeline-eventos-testkit-2026-10-24-aws-graviton3">Método, todos los resultados y notas de equidad</a>
    </div>
    <figure class="r4c-chart">
      <figcaption class="legend">
        <span><i style="background:var(--series-r4c)"></i>resilience4cata 0.1.0</span>
        <span><i style="background:var(--series-r4j)"></i>Resilience4j 2.4.0</span>
      </figcaption>
      <div class="row"><div class="row-label">Retry, el primer intento sale bien<small>1 thread</small></div>
        <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 1.2 ns por llamada, 0 B asignados"><i style="width:1.98%"></i><b>1.2 ns</b></span>
          <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 22.9 ns por llamada, 0 B asignados"><i style="width:34.0%"></i><b>22.9 ns</b></span></div></div>
      <div class="row"><div class="row-label">Circuit breaker, todas las llamadas salen bien<small>4 threads, un breaker compartido</small></div>
        <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 1.9 ns por llamada, 0 B asignados"><i style="width:6.97%"></i><b>1.9 ns</b></span>
          <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 832 ns por llamada, 2 B asignados"><i style="width:73.0%"></i><b>832 ns</b></span></div></div>
      <div class="row"><div class="row-label">Timeout síncrono, la llamada termina a tiempo<small>1 thread</small></div>
        <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 22.2 ns por llamada, 0 B asignados"><i style="width:33.7%"></i><b>22.2 ns</b></span>
          <span class="bar r4j" tabindex="0" data-tip="Resilience4j TimeLimiter sobre un executor: 8842 ns por llamada, 176 B asignados"><i style="width:98.7%"></i><b>8 842 ns</b></span></div></div>
      <div class="row"><div class="row-label">Bulkhead con lugares libres<small>1 thread · un empate</small></div>
        <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 20.5 ns por llamada, 0 B asignados"><i style="width:32.8%"></i><b>20.5 ns</b></span>
          <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 20.4 ns por llamada, 0 B asignados"><i style="width:32.7%"></i><b>20.4 ns</b></span></div></div>
      <div class="axis"><span></span><div><span>1 ns</span><span>10</span><span>100</span><span>1 000</span><span>10 000</span></div></div>
      <p class="axis-note">Tiempo por llamada, escala logarítmica. Menos es mejor.</p>
      <details><summary>Ver como tabla</summary>
        <div class="tablewrap"><table>
          <thead><tr><th>Escenario</th><th class="num">Threads</th><th class="num">resilience4cata</th><th class="num">Resilience4j</th></tr></thead>
          <tbody>
            <tr><td>Retry, el primer intento sale bien</td><td class="num">1</td><td class="num">1.2 ns, 0 B</td><td class="num">22.9 ns, 0 B</td></tr>
            <tr><td>Circuit breaker, todas las llamadas salen bien</td><td class="num">4</td><td class="num">1.9 ns, 0 B</td><td class="num">832 ns, 2 B</td></tr>
            <tr><td>Timeout síncrono</td><td class="num">1</td><td class="num">22.2 ns, 0 B</td><td class="num">8842 ns, 176 B</td></tr>
            <tr><td>Bulkhead con lugares libres</td><td class="num">1</td><td class="num">20.5 ns, 0 B</td><td class="num">20.4 ns, 0 B</td></tr>
          </tbody></table></div>
      </details>
      <div class="r4c-chart-tip" hidden></div>
    </figure>
  </div>
</section>
<section id="policies" aria-labelledby="pol-h">
  <div class="wrap">
    <h2 class="h2" id="pol-h">Ocho políticas, una anotación cada una</h2>
    <p class="lead">Ponlas en un método o en toda la interfaz. Combínalas como quieras: el procesador siempre las anida en el mismo orden.</p>
    <div class="panel">
      <a class="breaker" href="#/es/policies/retry"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 12a8 8 0 1 1-2.34-5.66"/><path d="M20 4.5V8.5H16"/><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/></svg></span><span><h3>@Retry</h3><p>Vuelve a intentar con backoff exponencial y jitter, dentro de un presupuesto de tiempo opcional.</p><span class="attrs">maxAttempts · delay · backoff · maxDuration</span></span></a>
      <a class="breaker" href="#/es/policies/hedge"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8h13"/><path d="M13 5l3 3-3 3"/><path d="M8 16h13"/><path d="M18 13l3 3-3 3"/></svg></span><span><h3>@Hedge</h3><p>Vuelve a lanzar la misma llamada async idempotente cuando va lenta, y se queda con el primer éxito.</p><span class="attrs">delay · maxAttempts · hedgeOn · budgetPercent</span></span></a>
      <a class="breaker" href="#/es/policies/circuit-breaker"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 16h4.5"/><circle cx="8" cy="16" r="1.6"/><path d="M9.2 15 L17 7.5"/><circle cx="16.5" cy="16" r="1.6"/><path d="M18 16h4"/></svg></span><span><h3>@CircuitBreaker</h3><p>Deja de llamar a una dependencia que sigue fallando y luego deja pasar algunas llamadas de prueba.</p><span class="attrs">failureRateThreshold · waitInOpen · halfOpenCalls</span></span></a>
      <a class="breaker" href="#/es/policies/rate-limiter"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 4h16l-5.5 6h-5z"/><path d="M12 10v1.5"/><circle cx="12" cy="15" r="1.3" fill="currentColor" stroke="none"/><circle cx="12" cy="20" r="1.3" fill="currentColor" stroke="none"/></svg></span><span><h3>@RateLimiter</h3><p>Admite un número fijo de llamadas por período y rechaza o hace esperar al resto.</p><span class="attrs">permits · period · maxWait</span></span></a>
      <a class="breaker" href="#/es/policies/adaptive-limit"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 17a8 8 0 1 1 16 0"/><path d="M12 17l4-5"/><circle cx="12" cy="17" r="1.4" fill="currentColor" stroke="none"/></svg></span><span><h3>@AdaptiveLimit</h3><p>Limita las llamadas concurrentes con un límite que se ajusta solo: sube mientras la latencia se mantiene, baja cuando crece.</p><span class="attrs">algorithm · minLimit · maxLimit</span></span></a>
      <a class="breaker" href="#/es/policies/timeout"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="13.5" r="7"/><path d="M12 13.5V9.5"/><path d="M10 3h4"/><path d="M12 3v3.5"/><path d="M18.2 6.8l1.4-1.4"/></svg></span><span><h3>@Timeout</h3><p>Interrumpe una llamada que se pasa de su presupuesto, en el propio thread de quien llama.</p><span class="attrs">value</span></span></a>
      <a class="breaker" href="#/es/policies/bulkhead"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8h18l-1.8 8.4a2 2 0 0 1-2 1.6H6.8a2 2 0 0 1-2-1.6z"/><path d="M9 8v10"/><path d="M15 8v10"/><path d="M12 8V4.5"/></svg></span><span><h3>@Bulkhead</h3><p>Limita cuántas llamadas corren a la vez, para que una dependencia lenta no se quede con todos los threads.</p><span class="attrs">maxConcurrent · maxWait</span></span></a>
      <a class="breaker" href="#/es/policies/fallback"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7h10"/><path d="M15.5 5.5l3 3"/><path d="M18.5 5.5l-3 3"/><path d="M7.5 7c0 6 3 10 8.5 10H21"/><path d="M18 14l3 3-3 3"/></svg></span><span><h3>@Fallback</h3><p>Devuelve una respuesta útil cuando todo lo demás falló, desde un método hermano o una clase handler.</p><span class="attrs">value · method</span></span></a>
    </div>
    <p class="order">Orden, de afuera hacia adentro: Fallback, Retry o Hedge, CircuitBreaker, RateLimiter, AdaptiveLimit, Timeout, Bulkhead y luego tu método. <a href="#/es/policies/">Cómo se combinan</a></p>
  </div>
</section>
<section aria-labelledby="qs-h">
  <div class="wrap">
    <h2 class="h2" id="qs-h">De la dependencia a la primera llamada protegida</h2>
    <ol class="steps">
      <li><h3>Agrega el procesador</h3><p>Core en tiempo de ejecución, las anotaciones solo al compilar y el procesador en el path de procesadores de anotaciones.</p>
<pre><span class="k">annotationProcessor</span> <span class="s">'dev.resilience4cata:</span>
<span class="s">    resilience4cata-processor'</span></pre></li>
      <li><h3>Anota una interfaz</h3><p>Las duraciones son strings como <code>"200ms"</code>, verificadas al compilar.</p>
<pre><span class="a">@Retry</span>(maxAttempts = <span class="n">3</span>)
<span class="a">@Timeout</span>(<span class="s">"2s"</span>)
<span class="t">Receipt</span> charge(<span class="t">Order</span> order);</pre></li>
      <li><h3>Envuelve tu implementación</h3><p>Un registry por aplicación comparte el estado de breakers y limiters por nombre.</p>
<pre><span class="t">Resilient</span>.of(<span class="t">PaymentClient</span>.<span class="k">class</span>,
    <span class="k">new</span> <span class="t">HttpPaymentClient</span>(), registry);</pre></li>
    </ol>
    <div class="cta-row">
      <a class="btn btn-primary" href="#/es/start/quickstart">Sigue el inicio rápido</a>
      <a class="btn btn-quiet" href="#/es/start/how-it-works">Cómo funciona</a>
    </div>
  </div>
</section>
<div class="foot-wrap">
  <div class="wrap foot">
    <div>
      <a class="r4c-brand" href="#/es/"><svg viewBox="0 0 40 40" aria-hidden="true"><rect width="40" height="40" rx="9" fill="#F2B500"/><g fill="none" stroke="#1A1F24" stroke-width="3.2" stroke-linecap="round"><path d="M4 27h7"/><path d="M13.6 25.4 L27 13"/><path d="M31 27h5"/></g><circle cx="13" cy="27" r="2.6" fill="#1A1F24"/><circle cx="29" cy="27" r="2.6" fill="none" stroke="#1A1F24" stroke-width="2.4"/></svg><span>resilience<b>4cata</b></span></a>
      <p>Resiliencia basada en anotaciones para Java 21+ puro. Licencia Apache 2.0.</p>
      <span class="coord">dev.resilience4cata:resilience4cata-bom:0.6.0</span>
    </div>
    <div><h4>Aprende</h4><ul><li><a href="#/es/start/installation">Instalación</a></li><li><a href="#/es/start/quickstart">Inicio rápido</a></li><li><a href="#/es/start/how-it-works">Cómo funciona</a></li><li><a href="#/es/guides/testing">Tests con ManualClock</a></li></ul></div>
    <div><h4>Referencia</h4><ul><li><a href="#/es/errors">Códigos de error</a></li><li><a href="#/es/reference/configuration">Claves de configuración</a></li><li><a href="#/es/performance">Rendimiento</a></li><li><a href="https://javadoc.io/doc/dev.resilience4cata">Javadoc</a></li></ul></div>
    <div><h4>Proyecto</h4><ul><li><a href="https://github.com/fabiangftech/resilience4cata">GitHub</a></li><li><a href="https://central.sonatype.com/namespace/dev.resilience4cata">Maven Central</a></li><li><a href="https://github.com/fabiangftech/resilience4cata/releases">Versiones</a></li><li><a href="https://www.apache.org/licenses/LICENSE-2.0">Licencia Apache 2.0</a></li><li><a href="#/en/" lang="en" hreflang="en">Documentation in English</a></li></ul></div>
  </div>
</div>
</div>
