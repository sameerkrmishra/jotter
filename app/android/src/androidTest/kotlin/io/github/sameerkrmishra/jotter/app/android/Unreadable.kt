package io.github.sameerkrmishra.jotter.app.android

import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable

/**
 * An extra that cannot be read back, like another app's class that Jotter does not have: it fails
 * when Android unparcels a share's extras, as a `BadParcelableException` from a missing class
 * would.
 */
class Unreadable : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeInt(1)
    }

    /** Fails to read itself back. */
    companion object CREATOR : Parcelable.Creator<Unreadable> {
        override fun createFromParcel(source: Parcel): Unreadable =
            throw BadParcelableException("Unreadable cannot be read")

        override fun newArray(size: Int): Array<Unreadable?> = arrayOfNulls(size)
    }
}
