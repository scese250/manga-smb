package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/**
 * Implementation of a vertical (top to bottom) PagerViewer for Manga.
 * It tracks the zoom level to persist it across pages and aligns to the right.
 */
class VerticalPagerMangaViewer(activity: ReaderActivity) : PagerViewer(activity) {

    var currentScale: Float = -1f

    /**
     * Creates a new vertical pager.
     */
    override fun createPager(): Pager {
        return Pager(activity, isHorizontal = false)
    }
}
