<div class="r4c-home">
<section class="hero" aria-labelledby="r4c-hero-title">
  <div class="wrap">
    <div class="hero-top">
      <div>
        <h1 id="r4c-hero-title">韧性，<br>在编译时生成。</h1>
        <p class="sub">在 Java 接口上标注重试、熔断器、超时等注解。编译时，注解处理器会生成实现这些策略的普通 Java 代码。没有代理，没有反射，不依赖框架。</p>
        <div class="ctas">
          <a class="btn btn-primary" href="/zh/start/quickstart">开始使用</a>
          <a class="btn btn-quiet" href="/zh/?id=benchmarks">查看基准测试</a>
        </div>
        <ul class="facts">
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Java 21+</li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>无需 Spring、CDI 或 AspectJ</li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>同步、<code>CompletionStage</code>、<code>Mono</code> 和 <code>Flux</code></li>
          <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>Apache 2.0</li>
        </ul>
      </div>
      <div>
        <div class="r4c-install">
          <div class="r4c-install-head">
            <div role="tablist" aria-label="构建工具">
              <button role="tab" id="r4c-t-g" data-tab="gradle" aria-controls="r4c-p-g" aria-selected="true">Gradle</button>
              <button role="tab" id="r4c-t-k" data-tab="gradle (kotlin)" aria-controls="r4c-p-k" aria-selected="false" tabindex="-1">Gradle (Kotlin)</button>
              <button role="tab" id="r4c-t-m" data-tab="maven" aria-controls="r4c-p-m" aria-selected="false" tabindex="-1">Maven</button>
            </div>
            <button class="r4c-copy" type="button">复制</button>
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
        <p class="install-note">Maven Central 上的 1.0.0-RC4 版本：一个<a href="/zh/blog/release-candidate">候选版本 (RC)</a>，API 已为 1.0 冻结。<code>resilience4cata-all</code> 包含 core、注解和配置；处理器只放在注解处理器路径上。<a href="/zh/start/installation">安装指南</a></p>
      </div>
    </div>
    <div class="r4c-compile" aria-label="带注解的接口，以及处理器据此生成的类">
      <div class="r4c-pane r4c-pane-src">
        <div class="r4c-pane-head"><strong>你编写</strong><code>PaymentClient.java</code></div>
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
        <span><b>javac</b><br>+ 处理器</span>
      </div>
      <div class="r4c-pane r4c-pane-gen">
        <div class="r4c-pane-head"><strong>处理器生成</strong><code>PaymentClient$Resilient.java</code></div>
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
      <div class="r4c-chain" role="group" aria-label="高亮某一层">
        <span>每次调用依次经过</span>
        <button type="button" data-layer="retry" aria-pressed="false">Retry</button><span class="wire"></span>
        <button type="button" data-layer="cb" aria-pressed="false">CircuitBreaker</button><span class="wire"></span>
        <button type="button" data-layer="timeout" aria-pressed="false">Timeout</button><span class="wire"></span>
        <span><code>HttpPaymentClient.charge</code></span>
        <span class="note">有删节：实际文件使用全限定名。</span>
      </div>
    </div>
  </div>
</section>
<section aria-labelledby="why">
  <div class="wrap">
    <h2 class="h2" id="why">保护逻辑是你能读懂的代码</h2>
    <p class="lead">Resilience4j 和 MicroProfile 在运行时用装饰器或代理包装你的调用。resilience4cata 在编译器中完成这项工作，所以运行的是普通 Java 代码。</p>
    <div class="why-grid">
      <div class="why">
        <h3>用调试器单步进入</h3>
        <p>处理器把 <code>PaymentClient$Resilient</code> 生成在你自己的类旁边。每个策略都只是某个方法中的几行直线代码，你可以打开它、在其中设置断点，并在堆栈跟踪中读到它。</p>
<pre>at HttpPaymentClient.charge
at PaymentClient$Resilient.r4c$timeout0
at PaymentClient$Resilient.r4c$circuitBreaker0
at PaymentClient$Resilient.charge
at CheckoutService.pay</pre>
      </div>
      <div class="why">
        <h3>同步调用成功时零内存分配</h3>
        <p>成功或被拒绝的同步调用分配 0 字节。每个 pull request 都会运行内存分配门禁的基准测试，一旦这一点改变，构建就会失败。异步调用会分配它们的 future，线程的第一次带超时调用会注册该线程一次：参见<a href="/zh/performance">性能</a>。</p>
        <div class="gate" role="img" aria-label="内存分配预算：重试成功、熔断器成功和舱壁隔离拒绝均为每次操作 0 字节">
          <div><span>Retry，成功</span><span class="ok">0 B/op</span></div>
          <div><span>CircuitBreaker，成功</span><span class="ok">0 B/op</span></div>
          <div><span>Bulkhead，拒绝</span><span class="ok">0 B/op</span></div>
        </div>
      </div>
      <div class="why">
        <h3>错误会让构建失败</h3>
        <p>错误的时长、签名不对的降级 (fallback) 方法、或标注在 final 方法上的注解，都是带有稳定错误码和修复建议的编译错误，而不是生产环境中的意外。</p>
<pre><span class="err">error:</span> [R4C-ATR-001] @Retry.delay = "2 seconds"
  is not a valid duration: … Use a number
  followed by ns, us, ms, s, m, h or d …
    @Retry(delay = "2 seconds")
    ^</pre>
        <p style="margin-top:12px"><a href="/zh/errors?id=r4c-atr-001">所有错误码及修复方法</a></p>
      </div>
    </div>
  </div>
</section>
<section id="benchmarks" aria-labelledby="bench-h">
  <div class="wrap bench">
    <div class="bench-meta">
      <h2 class="h2" id="bench-h">每个数字都经过测量</h2>
      <p class="lead" style="font-size:17px">本站的每个数字都来自仓库中的一次 JMH 运行，与配置等价的 Resilience4j 对比。我们没有胜出的地方，完整结果中都有说明。</p>
      <dl>
        <dt>机器</dt><dd>AWS c7g.xlarge，4 个 Graviton3 核心</dd>
        <dt>JMH</dt><dd>6 forks × 5 × 1 s, <code>-prof gc</code></dd>
        <dt>对比对象</dt><dd>Resilience4j 2.4.0</dd>
        <dt>日期</dt><dd>2026-11-02</dd>
      </dl>
      <p class="bench-score">在 <a href="/zh/benchmarks?id=frameworks">0.13.0 的完整运行</a>中，<b>156 项中胜出 150 项</b>。其余 6 项及其原因都列在那里。</p>
      <a href="/zh/benchmarks?id=release-candidate">方法、全部结果和公平性说明</a>
    </div>
    <figure class="r4c-chart">
      <figcaption class="legend">
        <span><i style="background:var(--series-r4c)"></i>resilience4cata 1.0.0-RC1</span>
        <span><i style="background:var(--series-r4j)"></i>Resilience4j 2.4.0</span>
        <span class="threads" role="radiogroup" aria-label="线程数"><label><input type="radio" name="bench-threads" value="1" checked>1 个线程</label><label><input type="radio" name="bench-threads" value="4">4 个线程</label></span>
      </figcaption>
      <div class="rows t1">
        <div class="row"><div class="row-label">重试，第一次尝试即成功</div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata：每次调用 1.4 ns，分配 0 B"><i style="width:2.9%"></i><b>1.4 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j：每次调用 22.9 ns，分配 0 B"><i style="width:27.2%"></i><b>22.9 ns<span class="bytes"> · 0 B</span></b></span></div>
          <div class="ratio"><b>16×</b><small>更快</small></div></div>
        <div class="row"><div class="row-label">熔断器，每次调用都成功<small>Resilience4j 使用 SYNCHRONIZED 模式</small></div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata：每次调用 1.9 ns，分配 0 B"><i style="width:5.6%"></i><b>1.9 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j：每次调用 89.9 ns，分配 0 B"><i style="width:39.1%"></i><b>89.9 ns<span class="bytes"> · 0 B</span></b></span></div>
          <div class="ratio"><b>47×</b><small>更快</small></div></div>
        <div class="row"><div class="row-label">同步超时，调用按时完成<small>Resilience4j：在 executor 上运行的 TimeLimiter</small></div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata：每次调用 23.6 ns，分配 0 B"><i style="width:27.5%"></i><b>23.6 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="在 executor 上运行的 Resilience4j TimeLimiter：每次调用 8907.6 ns，分配 183 B"><i style="width:79.0%"></i><b>8&nbsp;908 ns<span class="bytes"> · 183 B</span></b></span></div>
          <div class="ratio"><b>377×</b><small>更快</small></div></div>
        <div class="row"><div class="row-label">有空闲槽位的舱壁隔离</div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata：每次调用 17.3 ns，分配 0 B"><i style="width:24.8%"></i><b>17.3 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j：每次调用 20.1 ns，分配 0 B"><i style="width:26.1%"></i><b>20.1 ns<span class="bytes"> · 0 B</span></b></span></div>
          <div class="ratio"><b>1.2×</b><small>更快</small></div></div>
      </div>
      <div class="rows t4">
        <div class="row"><div class="row-label">重试，第一次尝试即成功</div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata：每次调用 1.3 ns，分配 0 B"><i style="width:2.3%"></i><b>1.3 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j：每次调用 23.0 ns，分配 0 B"><i style="width:27.2%"></i><b>23.0 ns<span class="bytes"> · 0 B</span></b></span></div>
          <div class="ratio"><b>18×</b><small>更快</small></div></div>
        <div class="row"><div class="row-label">熔断器，每次调用都成功<small>四个线程共享一个熔断器</small></div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata：每次调用 1.9 ns，分配 0 B"><i style="width:5.6%"></i><b>1.9 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j：每次调用 849.5 ns，分配 2 B"><i style="width:58.6%"></i><b>850 ns<span class="bytes"> · 2 B</span></b></span></div>
          <div class="ratio"><b>447×</b><small>更快</small></div></div>
        <div class="row"><div class="row-label">同步超时，调用按时完成<small>Resilience4j：在 executor 上运行的 TimeLimiter</small></div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata：每次调用 23.6 ns，分配 0 B"><i style="width:27.5%"></i><b>23.6 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="在 executor 上运行的 Resilience4j TimeLimiter：每次调用 9976.8 ns，分配 151 B"><i style="width:80.0%"></i><b>9&nbsp;977 ns<span class="bytes"> · 151 B</span></b></span></div>
          <div class="ratio"><b>423×</b><small>更快</small></div></div>
        <div class="row"><div class="row-label">有空闲槽位的舱壁隔离<small>四个线程共享一个舱壁</small></div>
          <div class="bars"><span class="bar r4c" tabindex="0" data-tip="resilience4cata：每次调用 192.3 ns，分配 0 B"><i style="width:45.7%"></i><b>192 ns<span class="bytes"> · 0 B</span></b></span>
            <span class="bar r4j" tabindex="0" data-tip="Resilience4j：每次调用 410.8 ns，分配 0 B"><i style="width:52.3%"></i><b>411 ns<span class="bytes"> · 0 B</span></b></span></div>
          <div class="ratio"><b>2.1×</b><small>更快</small></div></div>
      </div>
      <div class="axis"><span></span><div><span style="left:0%">1 ns</span><span style="left:20%">10 ns</span><span style="left:40%">100 ns</span><span style="left:60%">1 µs</span><span style="left:80%">10 µs</span><span style="left:100%">100 µs</span></div><span></span></div>
      <p class="axis-note">每次调用的耗时，对数刻度；右侧比值为 Resilience4j 的平均值除以我们的平均值。越低越好。平均值仅在 ARM (Graviton3) 上测得。熔断器这几行是每次调用都成功的情形；一旦有调用失败，差距会缩小（四个线程、每 10 次调用失败 1 次时为 3.4×），参见<a href="/zh/performance">性能</a>。</p>
      <details><summary>以表格显示</summary>
        <div class="tablewrap"><table>
          <thead><tr><th>场景</th><th class="num">线程数</th><th class="num">resilience4cata</th><th class="num">Resilience4j</th><th class="num">比值</th></tr></thead>
          <tbody>
            <tr><td>重试，第一次尝试即成功</td><td class="num">1</td><td class="num">1.4 ns, 0 B</td><td class="num">22.9 ns, 0 B</td><td class="num">16×</td></tr>
            <tr><td>重试，第一次尝试即成功</td><td class="num">4</td><td class="num">1.3 ns, 0 B</td><td class="num">23.0 ns, 0 B</td><td class="num">18×</td></tr>
            <tr><td>熔断器，每次调用都成功</td><td class="num">1</td><td class="num">1.9 ns, 0 B</td><td class="num">89.9 ns, 0 B</td><td class="num">47×</td></tr>
            <tr><td>熔断器，每次调用都成功</td><td class="num">4</td><td class="num">1.9 ns, 0 B</td><td class="num">849.5 ns, 2 B</td><td class="num">447×</td></tr>
            <tr><td>同步超时，调用按时完成</td><td class="num">1</td><td class="num">23.6 ns, 0 B</td><td class="num">8&nbsp;907.6 ns, 183 B</td><td class="num">377×</td></tr>
            <tr><td>同步超时，调用按时完成</td><td class="num">4</td><td class="num">23.6 ns, 0 B</td><td class="num">9&nbsp;976.8 ns, 151 B</td><td class="num">423×</td></tr>
            <tr><td>有空闲槽位的舱壁隔离</td><td class="num">1</td><td class="num">17.3 ns, 0 B</td><td class="num">20.1 ns, 0 B</td><td class="num">1.2×</td></tr>
            <tr><td>有空闲槽位的舱壁隔离</td><td class="num">4</td><td class="num">192.3 ns, 0 B</td><td class="num">410.8 ns, 0 B</td><td class="num">2.1×</td></tr>
          </tbody></table></div>
      </details>
      <div class="r4c-chart-tip" hidden></div>
    </figure>
  </div>
</section>
<section id="policies" aria-labelledby="pol-h">
  <div class="wrap">
    <h2 class="h2" id="pol-h">八种策略，每种一个注解</h2>
    <p class="lead">可以标注在方法上，也可以标注在整个接口上。随意叠加：处理器会按一个精心选择的顺序嵌套它们，<code>@PolicyOrder</code> 可以改变这个顺序。</p>
    <div class="panel">
      <a class="breaker" href="/zh/policies/retry"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 12a8 8 0 1 1-2.34-5.66"/><path d="M20 4.5V8.5H16"/><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/></svg></span><span><h3>@Retry</h3><p>使用指数退避和抖动再次尝试，可选总时间预算。</p><span class="attrs">maxAttempts · delay · backoff · maxDuration</span></span></a>
      <a class="breaker" href="/zh/policies/hedge"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8h13"/><path d="M13 5l3 3-3 3"/><path d="M8 16h13"/><path d="M18 13l3 3-3 3"/></svg></span><span><h3>@Hedge</h3><p>当幂等的异步调用变慢时再发起一次相同调用，采用最先成功的结果。</p><span class="attrs">delay · maxAttempts · hedgeOn · budgetPercent</span></span></a>
      <a class="breaker" href="/zh/policies/circuit-breaker"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 16h4.5"/><circle cx="8" cy="16" r="1.6"/><path d="M9.2 15 L17 7.5"/><circle cx="16.5" cy="16" r="1.6"/><path d="M18 16h4"/></svg></span><span><h3>@CircuitBreaker</h3><p>停止调用持续失败的依赖，之后放行少量探测调用。</p><span class="attrs">failureRateThreshold · waitInOpen · halfOpenCalls</span></span></a>
      <a class="breaker" href="/zh/policies/rate-limiter"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 4h16l-5.5 6h-5z"/><path d="M12 10v1.5"/><circle cx="12" cy="15" r="1.3" fill="currentColor" stroke="none"/><circle cx="12" cy="20" r="1.3" fill="currentColor" stroke="none"/></svg></span><span><h3>@RateLimiter</h3><p>每个周期只放行固定数量的调用，其余的被拒绝或等待。</p><span class="attrs">permits · period · maxWait</span></span></a>
      <a class="breaker" href="/zh/policies/adaptive-limit"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 17a8 8 0 1 1 16 0"/><path d="M12 17l4-5"/><circle cx="12" cy="17" r="1.4" fill="currentColor" stroke="none"/></svg></span><span><h3>@AdaptiveLimit</h3><p>用一个自行寻找合适值的上限限制并发调用：延迟稳定时上调，延迟升高时下调。</p><span class="attrs">algorithm · minLimit · maxLimit</span></span></a>
      <a class="breaker" href="/zh/policies/timeout"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="13.5" r="7"/><path d="M12 13.5V9.5"/><path d="M10 3h4"/><path d="M12 3v3.5"/><path d="M18.2 6.8l1.4-1.4"/></svg></span><span><h3>@Timeout</h3><p>在调用方自己的线程上中断超出时间预算的调用。</p><span class="attrs">value</span></span></a>
      <a class="breaker" href="/zh/policies/bulkhead"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8h18l-1.8 8.4a2 2 0 0 1-2 1.6H6.8a2 2 0 0 1-2-1.6z"/><path d="M9 8v10"/><path d="M15 8v10"/><path d="M12 8V4.5"/></svg></span><span><h3>@Bulkhead</h3><p>限制同时运行的调用数量，避免一个缓慢的依赖占满所有线程。</p><span class="attrs">maxConcurrent · maxWait</span></span></a>
      <a class="breaker" href="/zh/policies/fallback"><span class="handle"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7h10"/><path d="M15.5 5.5l3 3"/><path d="M18.5 5.5l-3 3"/><path d="M7.5 7c0 6 3 10 8.5 10H21"/><path d="M18 14l3 3-3 3"/></svg></span><span><h3>@Fallback</h3><p>在其他一切都失败时，通过同级方法或处理器类返回一个有用的结果。</p><span class="attrs">value · method</span></span></a>
    </div>
    <p class="order">顺序，从最外层开始：Fallback、Retry 或 Hedge、CircuitBreaker、RateLimiter、AdaptiveLimit、Timeout、Bulkhead，然后是你的方法。<a href="/zh/policies/">它们如何组合</a></p>
  </div>
</section>
<section aria-labelledby="qs-h">
  <div class="wrap">
    <h2 class="h2" id="qs-h">从添加依赖到第一次受保护的调用</h2>
    <ol class="steps">
      <li><h3>添加处理器</h3><p>core 在运行时使用，注解仅在编译时使用，处理器放在注解处理器路径上。</p>
<pre><span class="k">annotationProcessor</span> <span class="s">'dev.resilience4cata:</span>
<span class="s">    resilience4cata-processor'</span></pre></li>
      <li><h3>为接口添加注解</h3><p>时长是 <code>"200ms"</code> 这样的字符串，在编译时检查。</p>
<pre><span class="a">@Retry</span>(maxAttempts = <span class="n">3</span>)
<span class="a">@Timeout</span>(<span class="s">"2s"</span>)
<span class="t">Receipt</span> charge(<span class="t">Order</span> order);</pre></li>
      <li><h3>包装你的实现</h3><p>每个应用一个注册表 (registry)，按名称共享熔断器和限流器的状态。</p>
<pre><span class="t">Resilient</span>.of(<span class="t">PaymentClient</span>.<span class="k">class</span>,
    <span class="k">new</span> <span class="t">HttpPaymentClient</span>(), registry);</pre></li>
    </ol>
    <div class="cta-row">
      <a class="btn btn-primary" href="/zh/start/quickstart">跟随快速入门</a>
      <a class="btn btn-quiet" href="/zh/start/how-it-works">工作原理</a>
    </div>
  </div>
</section>
<div class="foot-wrap">
  <div class="wrap foot">
    <div>
      <a class="r4c-brand" href="/zh/"><svg viewBox="0 0 40 40" aria-hidden="true"><rect width="40" height="40" rx="9" fill="#F2B500"/><g fill="none" stroke="#1A1F24" stroke-width="3.2" stroke-linecap="round"><path d="M4 27h7"/><path d="M13.6 25.4 L27 13"/><path d="M31 27h5"/></g><circle cx="13" cy="27" r="2.6" fill="#1A1F24"/><circle cx="29" cy="27" r="2.6" fill="none" stroke="#1A1F24" stroke-width="2.4"/></svg><span>resilience<b>4cata</b></span></a>
      <p>面向纯 Java 21+ 的注解驱动韧性库。Apache License 2.0。</p>
      <span class="coord">dev.resilience4cata:resilience4cata-bom:1.0.0-RC4</span>
    </div>
    <div><h4>学习</h4><ul><li><a href="/zh/start/installation">安装</a></li><li><a href="/zh/start/quickstart">快速入门</a></li><li><a href="/zh/start/how-it-works">工作原理</a></li><li><a href="/zh/guides/testing">使用 ManualClock 测试</a></li></ul></div>
    <div><h4>参考</h4><ul><li><a href="/zh/errors">错误码</a></li><li><a href="/zh/reference/configuration">配置键</a></li><li><a href="/zh/performance">性能</a></li><li><a href="https://javadoc.io/doc/dev.resilience4cata">Javadoc</a></li></ul></div>
    <div><h4>项目</h4><ul><li><a href="https://github.com/fabiangftech/resilience4cata">GitHub</a></li><li><a href="https://central.sonatype.com/namespace/dev.resilience4cata">Maven Central</a></li><li><a href="https://github.com/fabiangftech/resilience4cata/releases">版本发布</a></li><li><a href="https://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a></li><li><a href="/en/" lang="en" hreflang="en">Documentation in English</a></li><li><a href="/es/" lang="es" hreflang="es">Documentación en español</a></li><li><a href="/pt/" lang="pt-BR" hreflang="pt-BR">Documentação em português</a></li></ul></div>
  </div>
</div>
</div>
