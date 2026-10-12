from pathlib import Path
r=Path(__file__).resolve().parents[1]
p=(r/'app/src/main/java/com/hisho/tether/Ptp.java').read_text()
c=(r/'app/src/main/java/com/hisho/tether/CaptureService.java').read_text()
assert 'canonCommandBusyRetry' in p
assert 'e.code!=0x2019' in p
assert 'Canon R8 detectada' in c
assert '0x330C' in c and '0x3113' in c
assert c.index('link.ready()') < c.index('List<int[]> initial=ptp.objects()')
print('PASS: R8 handshake resiliente e PTP pronto antes da varredura do cartão')
