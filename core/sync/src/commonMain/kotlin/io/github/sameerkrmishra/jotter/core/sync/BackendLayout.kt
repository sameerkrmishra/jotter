package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.core.model.DeviceId

/**
 * A segment file on a backend.
 *
 * @property number The number in its name.
 * @property hash The content hash in its name.
 * @property name Its file name.
 * @property info What the listing said of it.
 */
internal data class SegmentFile(
    val number: Long,
    val hash: String,
    val name: String,
    val info: BlobInfo,
)

/** The file layout on a backend (ADR-0004, ADR-0014). */
internal object BackendLayout {
    /** The folder of the format files: one per format and codec, so a path has one content. */
    const val FORMATS = "jotter/formats"
    const val DEVICES = "jotter/devices"

    private const val NUMBER_DIGITS = 12
    private const val HASH_DIGITS = 32
    private const val SEGMENT_SUFFIX = ".json.gz"
    private const val DEVICE_FILE = "device.json"

    /** The path segments of a segment file: `jotter/devices/<device>/<name>`. */
    private const val SEGMENT_PATH_PARTS = 4

    /** The format file of [format] with [codec], such as `jotter/formats/1-json+gzip.json`. */
    fun formatPath(
        format: Int,
        codec: String,
    ): String = "$FORMATS/$format-$codec.json"

    fun deviceFolder(device: DeviceId): String = "$DEVICES/$device"

    fun deviceFile(device: DeviceId): String = "${deviceFolder(device)}/$DEVICE_FILE"

    /** Returns the file name of the segment [number] with the content hash [hash]. */
    fun segmentName(
        number: Long,
        hash: String,
    ): String = "${number.toString().padStart(NUMBER_DIGITS, '0')}-$hash$SEGMENT_SUFFIX"

    fun segmentPath(
        device: DeviceId,
        name: String,
    ): String = "${deviceFolder(device)}/$name"

    /**
     * Returns the segment files in [files] by device, in order of number. Anything else in the
     * folders is ignored, as another version of Jotter may have put it there. A name written in
     * any but the one form [segmentName] gives is ignored too, so one file has one identity.
     */
    fun segments(files: List<BlobInfo>): Map<DeviceId, List<SegmentFile>> {
        val found = HashMap<DeviceId, MutableList<SegmentFile>>()
        files.forEach { file ->
            val parts = file.path.split('/')
            val device = parts.getOrNull(2)?.let(::canonicalDevice)
            val segment = parts.getOrNull(SEGMENT_PATH_PARTS - 1)?.let { segmentFile(it, file) }
            if (parts.size == SEGMENT_PATH_PARTS && device != null && segment != null) {
                found.getOrPut(device) { mutableListOf() } += segment
            }
        }
        val order = compareBy<SegmentFile>({ it.number }, { it.hash })
        return found.mapValues { (_, list) -> list.sortedWith(order) }
    }

    private fun canonicalDevice(text: String): DeviceId? =
        DeviceId.parseOrNull(text)?.takeIf { it.toString() == text }

    private fun segmentFile(
        name: String,
        info: BlobInfo,
    ): SegmentFile? {
        val stem = name.removeSuffix(SEGMENT_SUFFIX)
        val digits = stem.substringBefore('-')
        val hash = stem.substringAfter('-', "")
        val isSegment =
            name.endsWith(SEGMENT_SUFFIX) &&
                digits.length == NUMBER_DIGITS &&
                digits.all { it in '0'..'9' } &&
                hash.length == HASH_DIGITS &&
                hash.all { it in '0'..'9' || it in 'a'..'f' }
        val number = digits.takeIf { isSegment }?.toLong()?.takeIf { it > 0 }
        return number?.let { SegmentFile(it, hash, name, info) }
    }
}
