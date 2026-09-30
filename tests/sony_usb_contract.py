from pathlib import Path
root=Path(__file__).resolve().parents[1]
ptp=(root/'app/src/main/java/com/hisho/tether/Ptp.java').read_text()
svc=(root/'app/src/main/java/com/hisho/tether/CaptureService.java').read_text()
ui=(root/'app/src/main/java/com/hisho/tether/MainActivity.java').read_text()
build=(root/'build.py').read_text()
checks={
 'sony vendor accepted':'0x054C' in ui and 'supportedUsbCamera' in ui,
 'sony handshake':'void sonyCaptureMode()' in ptp and 'sonyCommandBusyRetry' in ptp and '0x9201,1,0,0' in ptp and '0x9202,0xC8' in ptp and '0x9201,3,0,0' in ptp,
 'sony endpoint retained':'USB_ENDPOINT_XFER_INT' in ptp,
 'sony safe polling':'List<int[]> sonyInitial=ptp.objects()' in svc and 'Sony novo objeto' in svc and 'ptp.pollEvent(700)' not in svc,
 'zve10 id':'pid==0x0D97' in svc,
 'canon path retained':'ptp.captureMode()' in svc and 'canonCommandBusyRetry' in ptp,
 'version':'0.15.8-sony-zve10-safe-poll' in build and "'--version-code','43'" in build,
}
for name,ok in checks.items():
    print(('OK   ' if ok else 'FAIL ')+name)
if not all(checks.values()): raise SystemExit(1)
