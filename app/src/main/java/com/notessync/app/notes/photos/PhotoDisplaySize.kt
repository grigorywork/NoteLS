package com.notessync.app.notes.photos

/** Display metadata only. Resizing never changes the original image file. */
data class PhotoDisplaySize(val width: Int = DEFAULT_WIDTH, val height: Int = DEFAULT_HEIGHT) {
    fun bounded() = PhotoDisplaySize(
        (if (width > 0) width else DEFAULT_WIDTH).coerceIn(MIN, MAX),
        (if (height > 0) height else DEFAULT_HEIGHT).coerceIn(MIN, MAX))
    fun drag(handle: PhotoHandle, dx: Int, dy: Int) = PhotoDisplaySize(
        (width.toLong() + handle.x * dx.toLong()).coerceIn(MIN.toLong(), MAX.toLong()).toInt(),
        (height.toLong() + handle.y * dy.toLong()).coerceIn(MIN.toLong(), MAX.toLong()).toInt())
    companion object {
        const val MIN = 144
        const val MAX = 720
        const val DEFAULT_WIDTH = 240
        const val DEFAULT_HEIGHT = 180
    }
}
enum class PhotoHandle(val x: Int, val y: Int, val label: String) {
    TOP_LEFT(-1, -1, "Левый верхний угол"), TOP(0, -1, "Верхний край"), TOP_RIGHT(1, -1, "Правый верхний угол"),
    LEFT(-1, 0, "Левый край"), RIGHT(1, 0, "Правый край"),
    BOTTOM_LEFT(-1, 1, "Левый нижний угол"), BOTTOM(0, 1, "Нижний край"), BOTTOM_RIGHT(1, 1, "Правый нижний угол")
}

/** Power-of-two sampling bound; all calculations use Long to handle corrupt extreme dimensions. */
fun photoSampleSize(width: Int, height: Int, limit: Int): Int {
    require(limit > 0)
    var sample = 1
    val longest = maxOf(width, height).coerceAtLeast(0).toLong()
    while (sample < (1 shl 30) && (longest + sample - 1) / sample > limit) sample *= 2
    return sample
}
