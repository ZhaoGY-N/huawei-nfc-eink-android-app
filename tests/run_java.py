import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
jdk = Path(os.environ['JAVA_HOME'])
(root / 'build').mkdir(exist_ok=True)
out = Path(tempfile.mkdtemp(prefix='protocol-tests-', dir=root / 'build'))
subprocess.run([str(jdk / 'bin/javac'), '-encoding', 'UTF-8', '--release', '8', '-d', str(out),
    str(root / 'src/org/nfcresearch/qpolllab/PanelProtocol.java'),
    str(root / 'src/org/nfcresearch/qpolllab/PanelType.java'),
    str(root / 'tests/PanelTypeTest.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ImageBlocks.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ImageRaster.java'),
    str(root / 'src/org/nfcresearch/qpolllab/CalibrationPalette.java'),
    str(root / 'src/org/nfcresearch/qpolllab/CalibratedRaster.java'),
    str(root / 'tests/CalibrationTest.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ImageAdjustments.java'),
    str(root / 'tests/ImageAdjustmentsTest.java'),
    str(root / 'src/org/nfcresearch/qpolllab/CropWindow.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ConnectionRetry.java'),
    str(root / 'src/org/nfcresearch/qpolllab/SessionFailure.java'),
    str(root / 'src/org/nfcresearch/qpolllab/NciDiscovery.java'),
    str(root / 'src/org/nfcresearch/qpolllab/BackendPolicy.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ReaderModeCompat.java'),
    str(root / 'src/org/nfcresearch/qpolllab/DirectQPoll.java'),
    str(root / 'src/org/nfcresearch/qpolllab/SessionWire.java'),
    str(root / 'tests/ReaderModeCompatTest.java'),
    str(root / 'tests/DirectQPollTest.java'),
    str(root / 'tests/SessionWireTest.java'),
    str(root / 'src/org/nfcresearch/qpolllab/PowerSession.java'),
    str(root / 'tests/PowerSessionTest.java'),
    str(root / 'tests/BackendPolicyTest.java'),
    str(root / 'tests/ConnectionRetryTest.java'),
    str(root / 'tests/SessionFailureTest.java'),
    str(root / 'tests/NciDiscoveryTest.java'),
    str(root / 'tests/ProtocolTest.java'), str(root / 'tests/ImageBlocksTest.java'),
    str(root / 'tests/ImageRasterTest.java'), str(root / 'tests/CropWindowTest.java')], check=True)
subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.ProtocolTest', str(out)], check=True)
subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.ImageBlocksTest'], check=True)
subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.ImageRasterTest', str(out)], check=True)
subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.CropWindowTest'], check=True)
for test in ('CalibrationTest', 'ImageAdjustmentsTest', 'PanelTypeTest', 'ConnectionRetryTest', 'SessionFailureTest', 'NciDiscoveryTest', 'BackendPolicyTest', 'PowerSessionTest', 'ReaderModeCompatTest', 'DirectQPollTest', 'SessionWireTest'):
    subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.' + test, str(out)], check=True)
(root / 'build/last-test-dir.txt').write_text(out.name, encoding='utf-8')
print('FIXTURES:', out)
