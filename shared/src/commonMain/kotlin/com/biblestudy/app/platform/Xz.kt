package com.biblestudy.app.platform

import okio.Source

/** Unpacks xz-packed data as it's read (the bundled databases ship packed). */
expect fun xz(packed: Source): Source
