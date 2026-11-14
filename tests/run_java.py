import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
jdk = Path(os.environ['JAVA_HOME'])
def java_tool(name):
    suffix = '.exe' if os.name == 'nt' else ''
    path = jdk / 'bin' / (name + suffix)
    if not path.is_file() and os.name == 'nt':
        path = jdk / 'bin' / name
    if not path.is_file():
        raise FileNotFoundError(str(path))
    return path
javac = java_tool('javac')
java = java_tool('java')
(root / 'build').mkdir(exist_ok=True)
out = Path(tempfile.mkdtemp(prefix='protocol-tests-', dir=root / 'build'))
subprocess.run([str(javac), '-encoding', 'UTF-8', '--release', '8', '-d', str(out),
    str(root / 'src/org/nfcresearch/qpolllab/PanelProtocol.java'),
    str(root / 'src/org/nfcresearch/qpolllab/PanelType.java'),
    str(root / 'src/org/nfcresearch/qpolllab/TestImageMode.java'),
    str(root / 'src/org/nfcresearch/qpolllab/RecentImageStore.java'),
    str(root / 'src/org/nfcresearch/qpolllab/WorkflowStatus.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ImageDraftStore.java'),
    str(root / 'tests/WorkflowStatusTest.java'),
    str(root / 'tests/ImageDraftStoreTest.java'),
    str(root / 'tests/TestImageModeTest.java'),
    str(root / 'tests/RecentImageStoreTest.java'),
    str(root / 'tests/PanelTypeTest.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ImageBlocks.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ImageRaster.java'),
    str(root / 'src/org/nfcresearch/qpolllab/CalibrationPalette.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ReinkRaster.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ColorMixPreset.java'),
    str(root / 'src/org/nfcresearch/qpolllab/CalibratedRaster.java'),
    str(root / 'tests/CalibrationTest.java'),
    str(root / 'tests/ColorMixTest.java'),
    str(root / 'tests/ReinkRasterTest.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ImageAdjustments.java'),
    str(root / 'tests/ImageAdjustmentsTest.java'),
    str(root / 'src/org/nfcresearch/qpolllab/CropWindow.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ConnectionRetry.java'),
    str(root / 'src/org/nfcresearch/qpolllab/SessionFailure.java'),
    str(root / 'src/org/nfcresearch/qpolllab/NciDiscovery.java'),
    str(root / 'src/org/nfcresearch/qpolllab/BackendPolicy.java'),
    str(root / 'src/org/nfcresearch/qpolllab/BackendOptions.java'),
    str(root / 'src/org/nfcresearch/qpolllab/NxpHalPolicy.java'),
    str(root / 'src/org/nfcresearch/qpolllab/HalLease.java'),
    str(root / 'src/org/nfcresearch/qpolllab/SessionApkPath.java'),
    str(root / 'tests/SessionApkPathTest.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ForegroundCleanup.java'),
    str(root / 'tests/NxpHalPolicyTest.java'),
    str(root / 'tests/ForegroundSessionTest.java'),
    str(root / 'tests/BackendOptionsTest.java'),
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
subprocess.run([str(java), '-cp', str(out), 'org.nfcresearch.qpolllab.ProtocolTest', str(out)], check=True)
subprocess.run([str(java), '-cp', str(out), 'org.nfcresearch.qpolllab.ImageBlocksTest'], check=True)
subprocess.run([str(java), '-cp', str(out), 'org.nfcresearch.qpolllab.ImageRasterTest', str(out)], check=True)
subprocess.run([str(java), '-cp', str(out), 'org.nfcresearch.qpolllab.CropWindowTest'], check=True)
for test in ('CalibrationTest', 'ColorMixTest', 'ImageAdjustmentsTest', 'PanelTypeTest', 'ConnectionRetryTest', 'SessionFailureTest', 'NciDiscoveryTest', 'BackendPolicyTest', 'BackendOptionsTest', 'PowerSessionTest', 'ReaderModeCompatTest', 'DirectQPollTest', 'SessionWireTest', 'TestImageModeTest', 'RecentImageStoreTest', 'WorkflowStatusTest', 'ImageDraftStoreTest'):
    subprocess.run([str(java), '-cp', str(out), 'org.nfcresearch.qpolllab.' + test, str(out)], check=True)
(root / 'build/last-test-dir.txt').write_text(out.name, encoding='utf-8')
print('FIXTURES:', out)
for test in ('ReinkRasterTest', 'NxpHalPolicyTest', 'ForegroundSessionTest', 'SessionApkPathTest'):
    subprocess.run([str(java), '-cp', str(out), 'org.nfcresearch.qpolllab.' + test], check=True)
