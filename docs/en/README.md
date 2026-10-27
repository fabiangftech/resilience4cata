<div class="r4c-home">
<section class="hero" aria-labelledby="r4c-hero-title">
  <div class="wrap">
    <div class="hero-top">
      <div>
        <h1 id="r4c-hero-title">Resilience,<br>compiled.</h1>
        <p class="sub">Annotate a Java interface with retry, circuit breaker, timeout and friends. At compile time, an annotation processor writes the plain Java that enforces them. No proxies, no reflection, no framework.</p>
        <div class="ctas">
          <a class="btn btn-primary" href="/en/start/quickstart">Get started</a>
          <a class="btn btn-quiet" href="/en/?id=benchmarks">See the benchmarks</a>
        </div>
        <ul class="facts">
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Java 21+</li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Works without Spring, CDI or AspectJ</li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Sync and <code>CompletionStage</code></li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Apache 2.0</li>
        </ul>
      </div>
      <div>
        <div class="r4c-install">
          <div class="r4c-install-head">
            <div role="tablist" aria-label="Build tool">
              <button role="tab" id="r4c-t-g" data-tab="gradle" aria-controls="r4c-p-g" aria-selected="true">Gradle</button>
              <button role="tab" id="r4c-t-k" data-tab="gradle (kotlin)" aria-controls="r4c-p-k" aria-selected="false" tabindex="-1">Gradle (Kotlin)</button>
              <button role="tab" id="r4c-t-m" data-tab="maven" aria-controls="r4c-p-m" aria-selected="false" tabindex="-1">Maven</button>
            </div>
            <button class="r4c-copy" type="button">Copy</button>
          </div>
          <div role="tabpanel" id="r4c-p-g" aria-labelledby="r4c-t-g"><pre><span class="k">dependencies</span> {
    <span class="k">implementation</span> <span class="s">'dev.resilience4cata:resilience4cata-all:0.9.0'</span>
    <span class="k">annotationProcessor</span> <span class="s">'dev.resilience4cata:resilience4cata-processor:0.9.0'</span>
}</pre></div>
          <div role="tabpanel" id="r4c-p-k" aria-labelledby="r4c-t-k" hidden><pre><span class="k">dependencies</span> {
    implementation(<span class="s">"dev.resilience4cata:resilience4cata-all:0.9.0"</span>)
    annotationProcessor(<span class="s">"dev.resilience4cata:resilience4cata-processor:0.9.0"</span>)
}</pre></div>
          <div role="tabpanel" id="r4c-p-m" aria-labelledby="r4c-t-m" hidden><pre>&lt;<span class="k">dependency</span>&gt;
  &lt;<span class="k">groupId</span>&gt;dev.resilience4cata&lt;/<span class="k">groupId</span>&gt;
  &lt;<span class="k">artifactId</span>&gt;resilience4cata-all&lt;/<span class="k">artifactId</span>&gt;
  &lt;<span class="k">version</span>&gt;0.9.0&lt;/<span class="k">version</span>&gt;
&lt;/<span class="k">dependency</span>&gt;
<span></span>
<span class="c">&lt;!-- in the maven-compiler-plugin &lt;configuration&gt; --&gt;</span>
&lt;<span class="k">annotationProcessorPaths</span>&gt;
  &lt;<span class="k">path</span>&gt;
    &lt;<span class="k">groupId</span>&gt;dev.resilience4cata&lt;/<span class="k">groupId</span>&gt;
    &lt;<span class="k">artifactId</span>&gt;resilience4cata-processor&lt;/<span class="k">artifactId</span>&gt;
    &lt;<span class="k">version</span>&gt;0.9.0&lt;/<span class="k">version</span>&gt;
  &lt;/<span class="k">path</span>&gt;
&lt;/<span class="k">annotationProcessorPaths</span>&gt;</pre></div>
        </div>
        <p class="install-note">Version 0.9.0 on Maven Central. <code>resilience4cata-all</code> brings core, the annotations and configuration; the processor only goes on the annotation processor path. <a href="/en/start/installation">Installation guide</a></p>
      </div>
    </div>
    <div class="r4c-compile" aria-label="Annotated interface and the class the processor generates from it">
      <div class="r4c-pane r4c-pane-src">
        <div class="r4c-pane-head"><strong>You write</strong><code>PaymentClient.java</code></div>
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
        <span><b>javac</b><br>+ processor</span>
      </div>
      <div class="r4c-pane r4c-pane-gen">
        <div class="r4c-pane-head"><strong>The processor writes</strong><code>PaymentClient$Resilient.java</code></div>
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
      <div class="r4c-chain" role="group" aria-label="Highlight a layer">
        <span>Each call passes through</span>
        <button type="button" data-layer="retry" aria-pressed="false">Retry</button><span class="wire"></span>
        <button type="button" data-layer="cb" aria-pressed="false">CircuitBreaker</button><span class="wire"></span>
        <button type="button" data-layer="timeout" aria-pressed="false">Timeout</button><span class="wire"></span>
        <span><code>HttpPaymentClient.charge</code></span>
        <span class="note">Abridged: the real file uses fully qualified names.</span>
      </div>
    </div>
  </div>
</section>
<section aria-labelledby="why">
  <div class="wrap">
    <h2 class="h2" id="why">The protection is code you can read</h2>
    <p class="lead">Resilience4j and MicroProfile wrap your calls in decorators or proxies at runtime. resilience4cata does that work in the compiler, so what runs is ordinary Java.</p>
    <div class="why-grid">
      <div class="why">
        <h3>Step into it with a debugger</h3>
        <p>The processor writes <code>PaymentClient$Resilient</code> next to your own classes. Every policy is a few straight lines in a method you can open, set a breakpoint in and read in a stack trace.</p>
<pre>at HttpPaymentClient.charge
at PaymentClient$Resilient.r4c$timeout0
at PaymentClient$Resilient.r4c$circuitBreaker0
at PaymentClient$Resilient.charge
at CheckoutService.pay</pre>
      </div>
      <div class="why">
        <h3>Nothing allocated when calls succeed</h3>
        <p>Synchronous calls that succeed or are rejected allocate zero bytes. An allocation gate runs the benchmarks on every pull request and fails the build if that changes.</p>
        <div class="gate" role="img" aria-label="Allocation budgets: retry success, circuit breaker success and bulkhead rejection are all 0 bytes per operation">
          <div><span>Retry, success</span><span class="ok">0 B/op</span></div>
          <div><span>CircuitBreaker, success</span><span class="ok">0 B/op</span></div>
          <div><span>Bulkhead, rejected</span><span class="ok">0 B/op</span></div>
        </div>
      </div>
      <div class="why">
        <h3>Mistakes fail the build</h3>
        <p>A bad duration, a fallback with the wrong signature or an annotation on a final method is a compile error with a stable code and a fix, not a surprise in production.</p>
<pre><span class="err">error:</span> [R4C-ATR-001] @Retry.delay = "2 seconds"
  is not a valid duration: … Use a number
  followed by ns, us, ms, s, m, h or d …
    @Retry(delay = "2 seconds")
    ^</pre>
        <p style="margin-top:12px"><a href="/en/errors?id=r4c-atr-001">Every error code, with a fix</a></p>
      </div>
    </div>
  </div>
</section>
<section id="benchmarks" aria-labelledby="bench-h">
  <div class="wrap bench">
    <div class="bench-meta">
      <h2 class="h2" id="bench-h">Measured, every time</h2>
      <p class="lead" style="font-size:17px">Every number on this site comes from a JMH run in the repository, against Resilience4j with equivalent settings. Where we don’t win, the table says so.</p>
      <dl>
        <dt>Machine</dt><dd>AWS c7g.xlarge, 4 Graviton3 cores</dd>
        <dt>JMH</dt><dd>3 forks × 5 × 1 s, <code>-prof gc</code></dd>
        <dt>Compared</dt><dd>Resilience4j 2.4.0</dd>
        <dt>Date</dt><dd>2026-10-24</dd>
      </dl>
      <a href="/en/benchmarks?id=mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3">Methods, all results and fairness notes</a>
    </div>
    <figure class="r4c-chart">
      <figcaption class="legend">
        <span><i style="background:var(--series-r4c)"></i>resilience4cata 0.1.0</span>
        <span><i style="background:var(--series-r4j)"></i>Resilience4j 2.4.0</span>
      </figcaption>
      <div class="row"><div class="row-label">Retry, first attempt succeeds<small>1 thread</small></div>
        <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 1.2 ns per call, 0 B allocated"><i style="width:1.98%"></i><b>1.2 ns</b></span>
          <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 22.9 ns per call, 0 B allocated"><i style="width:34.0%"></i><b>22.9 ns</b></span></div></div>
      <div class="row"><div class="row-label">Circuit breaker, every call succeeds<small>4 threads, one shared breaker</small></div>
        <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 1.9 ns per call, 0 B allocated"><i style="width:6.97%"></i><b>1.9 ns</b></span>
          <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 832 ns per call, 2 B allocated"><i style="width:73.0%"></i><b>832 ns</b></span></div></div>
      <div class="row"><div class="row-label">Sync timeout, call finishes in time<small>1 thread</small></div>
        <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 22.2 ns per call, 0 B allocated"><i style="width:33.7%"></i><b>22.2 ns</b></span>
          <span class="bar r4j" tabindex="0" data-tip="Resilience4j TimeLimiter on an executor: 8842 ns per call, 176 B allocated"><i style="width:98.7%"></i><b>8 842 ns</b></span></div></div>
      <div class="row"><div class="row-label">Bulkhead with free slots<small>1 thread · a tie</small></div>
        <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata: 20.5 ns per call, 0 B allocated"><i style="width:32.8%"></i><b>20.5 ns</b></span>
          <span class="bar r4j" tabindex="0" data-tip="Resilience4j: 20.4 ns per call, 0 B allocated"><i style="width:32.7%"></i><b>20.4 ns</b></span></div></div>
      <div class="axis"><span></span><div><span>1 ns</span><span>10</span><span>100</span><span>1 000</span><span>10 000</span></div></div>
      <p class="axis-note">Time per call, logarithmic scale. Lower is better.</p>
      <details><summary>Show as a table</summary>
        <div class="tablewrap"><table>
          <thead><tr><th>Scenario</th><th class="num">Threads</th><th class="num">resilience4cata</th><th class="num">Resilience4j</th></tr></thead>
          <tbody>
            <tr><td>Retry, first attempt succeeds</td><td class="num">1</td><td class="num">1.2 ns, 0 B</td><td class="num">22.9 ns, 0 B</td></tr>
            <tr><td>Circuit breaker, every call succeeds</td><td class="num">4</td><td class="num">1.9 ns, 0 B</td><td class="num">832 ns, 2 B</td></tr>
            <tr><td>Sync timeout</td><td class="num">1</td><td class="num">22.2 ns, 0 B</td><td class="num">8842 ns, 176 B</td></tr>
            <tr><td>Bulkhead with free slots</td><td class="num">1</td><td class="num">20.5 ns, 0 B</td><td class="num">20.4 ns, 0 B</td></tr>
          </tbody></table></div>
      </details>
      <div class="r4c-chart-tip" hidden></div>
    </figure>
  </div>
</section>
<section id="policies" aria-labelledby="pol-h">
  <div class="wrap">
    <h2 class="h2" id="pol-h">Eight policies, one annotation each</h2>
    <p class="lead">Put them on a method or on the whole interface. Stack them freely: the processor nests them in one well-chosen order, which <code>@PolicyOrder</code> can change.</p>
    <div class="panel">
      <a class="breaker" href="/en/policies/retry"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 12a8 8 0 1 1-2.34-5.66"/><path d="M20 4.5V8.5H16"/><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/></svg></span><span><h3>@Retry</h3><p>Try again with exponential backoff and jitter, within an optional time budget.</p><span class="attrs">maxAttempts · delay · backoff · maxDuration</span></span></a>
      <a class="breaker" href="/en/policies/hedge"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8h13"/><path d="M13 5l3 3-3 3"/><path d="M8 16h13"/><path d="M18 13l3 3-3 3"/></svg></span><span><h3>@Hedge</h3><p>Start the same idempotent async call again when it is slow, and keep the first success.</p><span class="attrs">delay · maxAttempts · hedgeOn · budgetPercent</span></span></a>
      <a class="breaker" href="/en/policies/circuit-breaker"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 16h4.5"/><circle cx="8" cy="16" r="1.6"/><path d="M9.2 15 L17 7.5"/><circle cx="16.5" cy="16" r="1.6"/><path d="M18 16h4"/></svg></span><span><h3>@CircuitBreaker</h3><p>Stop calling a dependency that keeps failing, then let a few probe calls through.</p><span class="attrs">failureRateThreshold · waitInOpen · halfOpenCalls</span></span></a>
      <a class="breaker" href="/en/policies/rate-limiter"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 4h16l-5.5 6h-5z"/><path d="M12 10v1.5"/><circle cx="12" cy="15" r="1.3" fill="currentColor" stroke="none"/><circle cx="12" cy="20" r="1.3" fill="currentColor" stroke="none"/></svg></span><span><h3>@RateLimiter</h3><p>Admit a fixed number of calls per period, rejecting or waiting for the rest.</p><span class="attrs">permits · period · maxWait</span></span></a>
      <a class="breaker" href="/en/policies/adaptive-limit"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 17a8 8 0 1 1 16 0"/><path d="M12 17l4-5"/><circle cx="12" cy="17" r="1.4" fill="currentColor" stroke="none"/></svg></span><span><h3>@AdaptiveLimit</h3><p>Cap concurrent calls with a limit that finds itself: up while latency holds, down when it rises.</p><span class="attrs">algorithm · minLimit · maxLimit</span></span></a>
      <a class="breaker" href="/en/policies/timeout"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="13.5" r="7"/><path d="M12 13.5V9.5"/><path d="M10 3h4"/><path d="M12 3v3.5"/><path d="M18.2 6.8l1.4-1.4"/></svg></span><span><h3>@Timeout</h3><p>Interrupt a call that runs past its budget, on the caller’s own thread.</p><span class="attrs">value</span></span></a>
      <a class="breaker" href="/en/policies/bulkhead"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8h18l-1.8 8.4a2 2 0 0 1-2 1.6H6.8a2 2 0 0 1-2-1.6z"/><path d="M9 8v10"/><path d="M15 8v10"/><path d="M12 8V4.5"/></svg></span><span><h3>@Bulkhead</h3><p>Cap how many calls run at the same time, so one slow dependency can’t take every thread.</p><span class="attrs">maxConcurrent · maxWait</span></span></a>
      <a class="breaker" href="/en/policies/fallback"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7h10"/><path d="M15.5 5.5l3 3"/><path d="M18.5 5.5l-3 3"/><path d="M7.5 7c0 6 3 10 8.5 10H21"/><path d="M18 14l3 3-3 3"/></svg></span><span><h3>@Fallback</h3><p>Return a useful answer when everything else has failed, from a sibling method or a handler class.</p><span class="attrs">value · method</span></span></a>
    </div>
    <p class="order">Order, outermost first: Fallback, Retry or Hedge, CircuitBreaker, RateLimiter, AdaptiveLimit, Timeout, Bulkhead, then your method. <a href="/en/policies/">How they combine</a></p>
  </div>
</section>
<section aria-labelledby="qs-h">
  <div class="wrap">
    <h2 class="h2" id="qs-h">From dependency to first protected call</h2>
    <ol class="steps">
      <li><h3>Add the processor</h3><p>Core at runtime, annotations compile-only, the processor on the annotation processor path.</p>
<pre><span class="k">annotationProcessor</span> <span class="s">'dev.resilience4cata:</span>
<span class="s">    resilience4cata-processor'</span></pre></li>
      <li><h3>Annotate an interface</h3><p>Durations are strings like <code>"200ms"</code>, checked when you compile.</p>
<pre><span class="a">@Retry</span>(maxAttempts = <span class="n">3</span>)
<span class="a">@Timeout</span>(<span class="s">"2s"</span>)
<span class="t">Receipt</span> charge(<span class="t">Order</span> order);</pre></li>
      <li><h3>Wrap your implementation</h3><p>One registry per application shares breaker and limiter state by name.</p>
<pre><span class="t">Resilient</span>.of(<span class="t">PaymentClient</span>.<span class="k">class</span>,
    <span class="k">new</span> <span class="t">HttpPaymentClient</span>(), registry);</pre></li>
    </ol>
    <div class="cta-row">
      <a class="btn btn-primary" href="/en/start/quickstart">Follow the quickstart</a>
      <a class="btn btn-quiet" href="/en/start/how-it-works">How it works</a>
    </div>
  </div>
</section>
<div class="foot-wrap">
  <div class="wrap foot">
    <div>
      <a class="r4c-brand" href="/en/"><svg viewBox="0 0 40 40" aria-hidden="true"><rect width="40" height="40" rx="9" fill="#F2B500"/><g fill="none" stroke="#1A1F24" stroke-width="3.2" stroke-linecap="round"><path d="M4 27h7"/><path d="M13.6 25.4 L27 13"/><path d="M31 27h5"/></g><circle cx="13" cy="27" r="2.6" fill="#1A1F24"/><circle cx="29" cy="27" r="2.6" fill="none" stroke="#1A1F24" stroke-width="2.4"/></svg><span>resilience<b>4cata</b></span></a>
      <p>Annotation-driven resilience for plain Java 21+. Apache License 2.0.</p>
      <span class="coord">dev.resilience4cata:resilience4cata-bom:0.9.0</span>
    </div>
    <div><h4>Learn</h4><ul><li><a href="/en/start/installation">Installation</a></li><li><a href="/en/start/quickstart">Quickstart</a></li><li><a href="/en/start/how-it-works">How it works</a></li><li><a href="/en/guides/testing">Testing with ManualClock</a></li></ul></div>
    <div><h4>Reference</h4><ul><li><a href="/en/errors">Error codes</a></li><li><a href="/en/reference/configuration">Configuration keys</a></li><li><a href="/en/performance">Performance</a></li><li><a href="https://javadoc.io/doc/dev.resilience4cata">Javadoc</a></li></ul></div>
    <div><h4>Project</h4><ul><li><a href="https://github.com/fabiangftech/resilience4cata">GitHub</a></li><li><a href="https://central.sonatype.com/namespace/dev.resilience4cata">Maven Central</a></li><li><a href="https://github.com/fabiangftech/resilience4cata/releases">Releases</a></li><li><a href="https://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a></li><li><a href="/es/" lang="es" hreflang="es">Documentación en español</a></li></ul></div>
  </div>
</div>
</div>
