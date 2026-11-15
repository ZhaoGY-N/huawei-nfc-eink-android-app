import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
jdk = Path(os.environ['JAVA_HOME'])

# Android regression guard: Canvas(bitmap) requires a mutable Bitmap. Keep this
# structural check in the default test path because the pure-Java test runner
# intentionally does not need an Android runtime or emulator.
panel_image = (root / 'src/org/nfcresearch/qpolllab/PanelImage.java').read_text(encoding='utf-8')
method_start = panel_image.index('public static Bitmap mixingPattern()')
method_end = panel_image.index('public static Bitmap tonePattern()', method_start)
mixing_method = panel_image[method_start:method_end]
mutable_allocation = 'Bitmap.createBitmap(TestChartPattern.WIDTH,TestChartPattern.HEIGHT,Bitmap.Config.ARGB_8888)'
set_pixels = 'bitmap.setPixels(pixels,0,TestChartPattern.WIDTH,0,0,TestChartPattern.WIDTH,TestChartPattern.HEIGHT)'
if mutable_allocation not in mixing_method or set_pixels not in mixing_method:
    raise AssertionError('mixingPattern must allocate a mutable Bitmap and copy chart pixels with setPixels()')
if mixing_method.index(set_pixels) > mixing_method.index('new Canvas(bitmap)'):
    raise AssertionError('mixingPattern must populate the mutable Bitmap before creating its Canvas')
if 'Bitmap.createBitmap(pixels,' in mixing_method:
    raise AssertionError('mixingPattern must not use the potentially immutable createBitmap(int[]) overload')
print('PanelImage mutable chart Bitmap regression: PASS')

# The two chart modes lock composition controls, not colour adjustment controls.
main_activity = (root / 'src/org/nfcresearch/qpolllab/MainActivity.java').read_text(encoding='utf-8')
if 'adjustments.setEditable(enabled&&!imageMode.fixedRaster())' not in main_activity:
    raise AssertionError('Colour adjustments must remain editable for both multilevel chart modes')
if 'boolean styleEditable=enabled&&!imageMode.fixedRaster();' not in main_activity:
    raise AssertionError('Rendering-method selector must remain available on the mixing chart for profile selection')
if 'adjustments.setProfile(pos)' not in main_activity:
    raise AssertionError('Selecting a rendering method must load its independent adjustment profile')
if 'new ImageAdjustmentUi(this,selectedRasterStyle()' not in main_activity:
    raise AssertionError('Initial adjustment profile must follow the persisted rendering method')

if 'ImageAdjustments effectiveAdjustments=adjustments;' not in panel_image:
    raise AssertionError('Style chart must use the active adjustment snapshot')
mixing_start = panel_image.index('if(mode==TestImageMode.MIXING)')
style_start = panel_image.index('boolean chart=mode==TestImageMode.STYLE_CHART;', mixing_start)
mixing_branch = panel_image[mixing_start:style_start]
style_branch = panel_image[style_start:panel_image.index('PanelType type=panel(panelType);byte[] packed=type.pack(codes);', style_start)]
if 'adjustments.applyTo(pixels)' not in mixing_branch:
    raise AssertionError('Mixing chart must apply brightness/temperature/contrast before quantization')
if 'effectiveAdjustments.applyTo(pixels)' not in style_branch:
    raise AssertionError('Style chart must apply brightness/temperature/contrast before quantization')
print('Independent adjustment profile and editable chart wiring: PASS')
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
    str(root / 'src/org/nfcresearch/qpolllab/TestChartPolicy.java'),
    str(root / 'src/org/nfcresearch/qpolllab/TestChartPattern.java'),
    str(root / 'src/org/nfcresearch/qpolllab/CalibratedRaster.java'),
    str(root / 'tests/CalibrationTest.java'),
    str(root / 'tests/ColorMixTest.java'),
    str(root / 'tests/TestChartRasterTest.java'),
    str(root / 'tests/ReinkRasterTest.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ImageAdjustments.java'),
    str(root / 'src/org/nfcresearch/qpolllab/ImageAdjustmentProfiles.java'),
    str(root / 'tests/ImageAdjustmentsTest.java'),
    str(root / 'tests/ImageAdjustmentProfilesTest.java'),
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
for test in ('CalibrationTest', 'ColorMixTest', 'TestChartRasterTest', 'ImageAdjustmentsTest', 'ImageAdjustmentProfilesTest', 'PanelTypeTest', 'ConnectionRetryTest', 'SessionFailureTest', 'NciDiscoveryTest', 'BackendPolicyTest', 'BackendOptionsTest', 'PowerSessionTest', 'ReaderModeCompatTest', 'DirectQPollTest', 'SessionWireTest', 'TestImageModeTest', 'RecentImageStoreTest', 'WorkflowStatusTest', 'ImageDraftStoreTest'):
    subprocess.run([str(java), '-cp', str(out), 'org.nfcresearch.qpolllab.' + test, str(out)], check=True)
(root / 'build/last-test-dir.txt').write_text(out.name, encoding='utf-8')
print('FIXTURES:', out)
for test in ('ReinkRasterTest', 'NxpHalPolicyTest', 'ForegroundSessionTest', 'SessionApkPathTest'):
    subprocess.run([str(java), '-cp', str(out), 'org.nfcresearch.qpolllab.' + test], check=True)
