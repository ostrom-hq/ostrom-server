# BENCH ONLY: one markdown table from the variants' results (ci/bench/run.sh).
import json, os, statistics, sys
root = sys.argv[1]; steady = int(sys.argv[2]); tick = os.sysconf('SC_CLK_TCK')
rows = []
for v in sys.argv[3:]:
    d = os.path.join(root, v)
    st = [l.split('\t') for l in open(f'{d}/startup.tsv').read().split('\n') if l]
    k = json.load(open(f'{d}/k6.json'))['metrics']
    def m(name, stat):
        x = k.get(name, {}).get('values', {})
        return x.get(stat, float('nan'))
    t0 = int(open(f'{d}/load-start.txt').read()) + 120; t1 = t0 + steady * 60
    s = [list(map(int, l.split('\t'))) for l in open(f'{d}/samples.tsv').read().split('\n') if l.count('\t') == 3]
    win = [x for x in s if t0 <= x[0] <= t1]
    late = [x for x in win if x[0] >= t1 - 120]
    cpu = (win[-1][3] - win[0][3]) / tick if len(win) > 1 else float('nan')
    reqs = m('http_reqs{scenario:steady}', 'count')
    rows.append([v,
        statistics.median(int(x[1]) for x in st), statistics.median(int(x[2]) for x in st) // 1024,
        open(f'{d}/idle.txt').read().split()[0].split('=')[1],
        m('http_reqs{scenario:steady}', 'rate'),
        m('http_req_duration{scenario:steady}', 'p(50)'), m('http_req_duration{scenario:steady}', 'p(95)'),
        m('http_req_duration{scenario:steady}', 'p(99)'),
        m('http_req_duration{scenario:steady,name:ask}', 'p(99)'),
        m('http_req_failed{scenario:steady}', 'rate'),
        max(x[1] for x in win) // 1024 if win else 'nan', statistics.mean(x[1] for x in late) // 1024 if late else 'nan',
        max(x[2] for x in win) if win else 'nan',
        cpu, cpu / reqs * 1000 if reqs else float('nan'),
        m('http_reqs{scenario:max}', 'rate'), m('http_req_duration{scenario:max}', 'p(50)'),
        m('http_req_duration{scenario:max}', 'p(99)'), m('http_req_failed{scenario:max}', 'rate'),
        max(x[1] for x in s) // 1024, open(f'{d}/after.txt').read().split()[0].split('=')[1]])
head = ['variant', 'start ms (median of 5)', 'RSS at ready MiB', 'idle RSS KiB', 'steady req/s', 'p50 ms', 'p95 ms',
        'p99 ms', 'ask p99 ms', 'steady errors', 'steady peak RSS MiB', 'RSS last 2 min MiB', 'peak threads',
        'CPU s (steady)', 'CPU ms per req', 'max req/s (closed loop)', 'max p50 ms', 'max p99 ms', 'max errors',
        'peak RSS overall MiB', 'RSS 30 s after KiB']
def f(x): return f'{x:.2f}' if isinstance(x, float) else str(x)
print('| ' + ' | '.join(head) + ' |'); print('|' + ' --- |' * len(head))
for r in rows: print('| ' + ' | '.join(f(x) for x in r) + ' |')
