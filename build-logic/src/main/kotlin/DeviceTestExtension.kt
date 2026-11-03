/**
 * `deviceTest { }`, added by jotter.android-device-test: what a module changes in its device tests.
 */
class DeviceTestExtension(
    /**
     * The arguments for AndroidJUnitRunner, such as `notClass` or `notPackage` to leave out tests
     * that cannot run on a device. This is AGP's own map, which it reads after the build script.
     */
    val runnerArguments: MutableMap<String, String>,
)
