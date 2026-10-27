import {css, html, LitElement} from 'lit';
import {JsonRpc} from 'jsonrpc';
import '@vaadin/grid';
import '@vaadin/button';
import '@vaadin/details';
import {columnBodyRenderer} from '@vaadin/grid/lit.js';

/**
 * resilience4cata's Dev UI page: every policy instance the registry has created, refreshed every two seconds while
 * the page is visible. Circuit breakers can be forced open, disabled or reset (dev mode only).
 */
export class QwcResilience4cataPolicies extends LitElement {
    jsonRpc = new JsonRpc(this);

    static styles = css`
      :host {
        display: flex;
        flex-direction: column;
        gap: 1em;
        padding: 0 1em 1em 1em;
      }
      vaadin-grid {
        height: auto;
      }
      .empty {
        color: var(--lumo-secondary-text-color);
      }
      .state-CLOSED { color: var(--lumo-success-text-color); }
      .state-OPEN, .state-FORCED_OPEN { color: var(--lumo-error-text-color); }
      .state-HALF_OPEN { color: var(--lumo-warning-text-color, orange); }
      .state-DISABLED { color: var(--lumo-secondary-text-color); }
      .note {
        color: var(--lumo-secondary-text-color);
        font-size: var(--lumo-font-size-s);
      }
    `;

    static properties = {
        _snapshot: {state: true},
        _error: {state: true}
    };

    connectedCallback() {
        super.connectedCallback();
        this._refresh();
        this._timer = setInterval(() => {
            if (document.visibilityState === 'visible') {
                this._refresh();
            }
        }, 2000);
    }

    disconnectedCallback() {
        clearInterval(this._timer);
        super.disconnectedCallback();
    }

    _refresh() {
        this.jsonRpc.getSnapshot()
            .then(response => { this._snapshot = response.result; this._error = undefined; })
            .catch(error => { this._error = error; });
    }

    _act(method, instance) {
        this.jsonRpc[method]({instance: instance}).then(() => this._refresh());
    }

    render() {
        if (this._error && !this._snapshot) {
            return html`<span>Could not read the registry: ${this._error}</span>`;
        }
        if (!this._snapshot) {
            return html`<span>Loading resilience4cata policies...</span>`;
        }
        const s = this._snapshot;
        return html`
            <span class="note">Instances appear once created (most on first use). Refreshed every 2 s.</span>
            ${this._section('Circuit breakers', s.circuitBreakers, [
                ['Instance', b => html`<code>${b.instance}</code>`],
                ['State', b => html`<span class="state-${b.state}">${b.state}</span>`],
                ['Failure rate', b => this._percent(b.failureRate)],
                ['Slow-call rate', b => this._percent(b.slowCallRate)],
                ['Calls in window', b => html`${b.bufferedCalls} (${b.failedCalls} failed, ${b.slowCalls} slow)`],
                ['Actions (dev mode)', b => html`
                    <vaadin-button theme="small error" @click=${() => this._act('forceOpen', b.instance)}>Force open</vaadin-button>
                    <vaadin-button theme="small" @click=${() => this._act('disable', b.instance)}>Disable</vaadin-button>
                    <vaadin-button theme="small primary" @click=${() => this._act('reset', b.instance)}>Reset</vaadin-button>`]
            ])}
            ${this._section('Bulkheads', s.bulkheads, [
                ['Instance', b => html`<code>${b.instance}</code>`],
                ['Concurrent calls', b => html`${b.concurrentCalls} / ${b.maxConcurrent}`],
                ['Waiting', b => html`${b.waitingCalls}`]
            ])}
            ${this._section('Rate limiters', s.rateLimiters, [
                ['Instance', r => html`<code>${r.instance}</code>`],
                ['Available permits', r => html`${r.availablePermits} / ${r.permits}`]
            ])}
            ${this._section('Adaptive limits', s.adaptiveLimits, [
                ['Instance', a => html`<code>${a.instance}</code>`],
                ['Algorithm', a => html`${a.algorithm}`],
                ['Limit', a => html`${a.limit} (${a.minLimit}–${a.maxLimit})`],
                ['In flight', a => html`${a.inFlight}`],
                ['Last window latency', a => this._value(a.windowLatency)],
                ['Long-term latency', a => this._value(a.longTermLatency)],
                ['Windows', a => html`${a.windows}`]
            ])}
            ${this._section('Retries', s.retries, [
                ['Instance', r => html`<code>${r.instance}</code>`],
                ['Max attempts', r => html`${r.maxAttempts}`],
                ['Budget', r => r.budget
                    ? html`${r.budget.retries} of ${r.budget.allowedRetries} retries allowed, ${r.budget.calls} calls`
                    : this._value(null)]
            ])}
            ${this._section('Hedges', s.hedges, [
                ['Instance', h => html`<code>${h.instance}</code>`],
                ['Delay', h => html`${h.delay}`],
                ['Max attempts', h => html`${h.maxAttempts}`],
                ['Budget', h => h.budget
                    ? html`${h.budget.hedges} of ${h.budget.allowedHedges} hedges allowed, ${h.budget.calls} calls`
                    : this._value(null)]
            ])}
            ${this._section('Timeouts', s.timeouts, [
                ['Instance', t => html`<code>${t.instance}</code>`],
                ['Timeout', t => html`${t.timeout}`]
            ])}
            <span class="note">Docs: <a href="https://resilience4cata.dev/en/guides/quarkus#dev-ui" target="_blank">resilience4cata.dev</a></span>
        `;
    }

    _section(title, items, columns) {
        return html`
            <vaadin-details summary="${title} (${items.length})" opened>
                ${items.length === 0
                    ? html`<span class="empty">None yet.</span>`
                    : html`
                        <vaadin-grid .items="${items}" theme="no-border row-stripes" all-rows-visible>
                            ${columns.map(([header, render]) => html`
                                <vaadin-grid-column header="${header}" auto-width resizable
                                    ${columnBodyRenderer(render, [])}></vaadin-grid-column>`)}
                        </vaadin-grid>`}
            </vaadin-details>`;
    }

    _percent(value) {
        return value === null || value === undefined ? this._value(null) : html`${value.toFixed(1)} %`;
    }

    _value(value) {
        return value === null || value === undefined ? html`<span class="empty">—</span>` : html`${value}`;
    }
}
customElements.define('qwc-resilience4cata-policies', QwcResilience4cataPolicies);
