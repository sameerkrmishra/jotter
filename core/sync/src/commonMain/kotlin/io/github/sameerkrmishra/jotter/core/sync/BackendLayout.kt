package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.core.model.DeviceId

/** The file layout on a backend (ADR-0004). */
internal object BackendLayout {
    const val FORMAT_PATH = "jotter/format.json"
    const val DEVICES = "jotter/devices"

    private const val NUMBER_DIGITS = 12
    private const val SEGMENT_SUFFIX = ".json.gz"
    private const val DEVICE_FILE = "device.json"

    /** The path segments of a segment file: `jotter/devices/<device>/<number>.json.gz`. */
    private const val SEGMENT_PATH_PARTS = 4

    fun deviceFolder(device: DeviceId): String = "$DEVICES/$device"

    fun deviceFile(device: DeviceId): String = "${deviceFolder(device)}/$DEVICE_FILE"

    fun segmentPath(
        device: DeviceId,
        number: Long,
    ): String =
        "${deviceFolder(device)}/${number.toString().padStart(NUMBER_DIGITS, '0')}$SEGMENT_SUFFIX"

    /**
     * Returns the segment files in [files] by device and number. Anything else in the folders is
     * ignored, as another version of Jotter may have put it there. A folder or number written in
     * any but the one form [segmentPath] gives is ignored too, so one file has one identity.
     */
    fun segments(files: List<BlobInfo>): Map<DeviceId, Map<Long, BlobInfo>> {
        val found = HashMap<DeviceId, MutableMap<Long, BlobInfo>>()
        files.forEach { file ->
            val parts = file.path.split('/')
            val device = parts.getOrNull(2)?.let(::canonicalDevice)
            val number = parts.getOrNull(SEGMENT_PATH_PARTS - 1)?.let(::segmentNumber)
            if (parts.size == SEGMENT_PATH_PARTS && device != null && number != null) {
                found.getOrPut(device) { HashMap() }[number] = file
            }
        }
        return found
    }

    private fun canonicalDevice(text: String): DeviceId? =
        DeviceId.parseOrNull(text)?.takeIf { it.toString() == text }

    private fun segmentNumber(name: String): Long? {
        val digits = name.removeSuffix(SEGMENT_SUFFIX)
        val isNumber =
            name.endsWith(SEGMENT_SUFFIX) &&
                digits.length == NUMBER_DIGITS &&
                digits.all { it in '0'..'9' }
        return digits.takeIf { isNumber }?.toLong()?.takeIf { it > 0 }
    }
}
