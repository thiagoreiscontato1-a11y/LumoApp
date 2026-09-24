from pathlib import Path
root=Path(__file__).resolve().parents[1]
main=(root/'app/src/main/java/com/hisho/tether/MainActivity.java').read_text()
for item in ['BATERIA','ESPAÇO','PENDENTES','PROCESSADAS','ENVIADAS']:
    assert f'metricChip("{item}")' in main
assert 'buildHeaderMetrics(header)' in main
assert 'compactHeader()' in main
assert 'metricSent.setText("ENVIADAS\\n"+sent)' in main
print('PASS: topo em mini-cards/chips responsivos para retrato e paisagem.')
