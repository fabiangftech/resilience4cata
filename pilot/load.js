// The pilot's load: 10 minutes of orders (every sync policy) and quotes (a rate limiter) at a fixed arrival rate, with
// the backend going down at minute 4 and slow at minute 6. MINUTE (seconds, default 60) shrinks the run for a local try.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const APP = __ENV.APP_URL || 'http://127.0.0.1:8080';
const BACKEND = __ENV.BACKEND_URL || 'http://127.0.0.1:9000';
const MINUTE = Number(__ENV.MINUTE || 60);
const ORDERS = Number(__ENV.ORDERS_RATE || 1500); // per second
const QUOTES = Number(__ENV.QUOTES_RATE || 300); // per second; the limiter allows 200
const s = (minutes) => `${Math.round(minutes * MINUTE)}s`;

// [name, from minute, to minute, backend mode]
const PHASES = [
    ['warmup', 0, 1, 'normal'],
    ['steady', 1, 4, 'normal'],
    ['down', 4, 5, 'down'],
    ['recovery', 5, 6, 'normal'],
    ['slow', 6, 7, 'slow'],
    ['recovery2', 7, 8, 'normal'],
    ['after', 8, 10, 'normal'],
];
const OUTCOMES = ['ok', 'fallback_open', 'fallback_bulkhead', 'fallback_timeout', 'fallback_backend', 'fallback_other',
    'limited', 'failed', 'error'];

const latency = {};
const orders = {};
const quotes = {};
for (const [phase] of PHASES) {
    latency[phase] = new Trend(`latency_${phase}`, true);
    for (const outcome of OUTCOMES) {
        orders[`${phase}_${outcome}`] = new Counter(`orders_${phase}_${outcome}`);
        quotes[`${phase}_${outcome}`] = new Counter(`quotes_${phase}_${outcome}`);
    }
}

const chaos = {};
PHASES.slice(1).forEach(([name, from, , mode]) => {
    chaos[`backend_${name}`] = {
        executor: 'per-vu-iterations', vus: 1, iterations: 1, startTime: s(from), exec: 'switchBackend',
        env: { MODE: mode }, maxDuration: '30s',
    };
});

export const options = {
    discardResponseBodies: false,
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(99)', 'p(99.9)', 'max', 'count'],
    scenarios: {
        orders: {
            executor: 'ramping-arrival-rate', exec: 'order', startRate: Math.round(ORDERS / 10), timeUnit: '1s',
            preAllocatedVUs: 300, maxVUs: 3000,
            stages: [{ target: ORDERS, duration: s(1) }, { target: ORDERS, duration: s(9) }],
        },
        quotes: {
            executor: 'constant-arrival-rate', exec: 'quote', rate: QUOTES, timeUnit: '1s', duration: s(10),
            preAllocatedVUs: 400, maxVUs: 2000,
        },
        ...chaos,
    },
};

function phaseNow() {
    const minute = (Date.now() - exec.scenario.startTime) / 1000 / MINUTE;
    for (const [name, from, to] of PHASES) {
        if (minute < to) {
            return name;
        }
    }
    return PHASES[PHASES.length - 1][0];
}

function outcome(res) {
    if (res.status === 200) {
        const body = res.body || '';
        if (body === 'ok') return 'ok';
        if (body.includes('CallNotPermitted')) return 'fallback_open';
        if (body.includes('BulkheadFull')) return 'fallback_bulkhead';
        if (body.includes('CallTimeout')) return 'fallback_timeout';
        if (body.includes('BackendException')) return 'fallback_backend';
        return 'fallback_other';
    }
    if (res.status === 429) return 'limited';
    if (res.status === 502) return 'failed';
    return 'error';
}

export function order() {
    const phase = phaseNow();
    const res = http.get(`${APP}/orders/${exec.scenario.iterationInTest % 1000}`, { timeout: '10s', tags: { name: 'orders' } });
    latency[phase].add(res.timings.duration);
    orders[`${phase}_${outcome(res)}`].add(1);
}

export function quote() {
    const phase = phaseNow();
    const res = http.get(`${APP}/quotes/${exec.scenario.iterationInTest % 1000}`, { timeout: '10s', tags: { name: 'quotes' } });
    quotes[`${phase}_${outcome(res)}`].add(1);
}

export function switchBackend() {
    http.post(`${BACKEND}/admin/mode/${__ENV.MODE}`);
}

export function handleSummary(data) {
    const out = __ENV.SUMMARY || 'k6-summary.json';
    return { [out]: JSON.stringify(data, null, 1), stdout: `summary written to ${out}\n` };
}
