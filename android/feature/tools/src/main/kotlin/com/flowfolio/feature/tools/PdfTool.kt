package com.flowfolio.feature.tools

import androidx.annotation.StringRes
import com.flowfolio.core.designsystem.Glyph

/** What a tool takes in. */
enum class ToolInput { ONE_PDF, MANY_PDF, IMAGES, BOOK, NONE }

/** The PDF tools, after Stirling-PDF's, that run on the phone. */
enum class PdfTool(
    @StringRes val title: Int,
    @StringRes val sub: Int,
    val glyph: Glyph,
    val input: ToolInput = ToolInput.ONE_PDF
) {
    MERGE(R.string.tool_merge, R.string.tool_merge_sub, Glyph.Shelves, ToolInput.MANY_PDF),
    SPLIT(R.string.tool_split, R.string.tool_split_sub, Glyph.Sort),
    EXTRACT(R.string.tool_extract, R.string.tool_extract_sub, Glyph.GoTo),
    REMOVE(R.string.tool_remove, R.string.tool_remove_sub, Glyph.Trash),
    ROTATE(R.string.tool_rotate, R.string.tool_rotate_sub, Glyph.Refresh),
    REORDER(R.string.tool_reorder, R.string.tool_reorder_sub, Glyph.Direction),
    WATERMARK(R.string.tool_watermark, R.string.tool_watermark_sub, Glyph.Highlight),
    NUMBERS(R.string.tool_numbers, R.string.tool_numbers_sub, Glyph.Contents),
    COMPRESS(R.string.tool_compress, R.string.tool_compress_sub, Glyph.Storage),
    PROTECT(R.string.tool_protect, R.string.tool_protect_sub, Glyph.Bookmark),
    UNLOCK(R.string.tool_unlock, R.string.tool_unlock_sub, Glyph.Select),
    METADATA(R.string.tool_metadata, R.string.tool_metadata_sub, Glyph.Edit),
    CROP(R.string.tool_crop, R.string.tool_crop_sub, Glyph.Margins),
    FLATTEN(R.string.tool_flatten, R.string.tool_flatten_sub, Glyph.Page),
    IMAGES_TO_PDF(R.string.tool_images_to_pdf, R.string.tool_images_to_pdf_sub, Glyph.Cover, ToolInput.IMAGES),
    PDF_TO_IMAGES(R.string.tool_pdf_to_images, R.string.tool_pdf_to_images_sub, Glyph.OpenBook),
    TEXT(R.string.tool_text, R.string.tool_text_sub, Glyph.TextSize),
    BLANK_PAGES(R.string.tool_blank, R.string.tool_blank_sub, Glyph.Trash),
    SANITIZE(R.string.tool_sanitize, R.string.tool_sanitize_sub, Glyph.Select),
    REPAIR(R.string.tool_repair, R.string.tool_repair_sub, Glyph.Refresh),
    N_UP(R.string.tool_nup, R.string.tool_nup_sub, Glyph.Page),
    SINGLE_PAGE(R.string.tool_single, R.string.tool_single_sub, Glyph.Page),
    EXTRACT_IMAGES(R.string.tool_extract_images, R.string.tool_extract_images_sub, Glyph.Cover),
    CONVERT_BOOK(R.string.tool_convert_book, R.string.tool_convert_book_sub, Glyph.OpenBook, ToolInput.BOOK),
    SCALE_A4(R.string.tool_scale_a4, R.string.tool_scale_a4_sub, Glyph.Page),
    BOOKLET(R.string.tool_booklet, R.string.tool_booklet_sub, Glyph.Page),
    SPLIT_SIZE(R.string.tool_split_size, R.string.tool_split_size_sub, Glyph.Sort),
    SPLIT_BOOKMARKS(R.string.tool_split_bookmarks, R.string.tool_split_bookmarks_sub, Glyph.Sort),
    REMOVE_IMAGES(R.string.tool_remove_images, R.string.tool_remove_images_sub, Glyph.Trash),
    REMOVE_ANNOTATIONS(R.string.tool_remove_annotations, R.string.tool_remove_annotations_sub, Glyph.Trash),
    EXTRACT_ATTACHMENTS(R.string.tool_extract_attachments, R.string.tool_extract_attachments_sub, Glyph.Cover),
    OVERLAY(R.string.tool_overlay, R.string.tool_overlay_sub, Glyph.Page, ToolInput.MANY_PDF),
    HEADER(R.string.tool_header, R.string.tool_header_sub, Glyph.TextSize),
    FOOTER(R.string.tool_footer, R.string.tool_footer_sub, Glyph.TextSize),
    COMPARE(R.string.tool_compare, R.string.tool_compare_sub, Glyph.Select, ToolInput.MANY_PDF),
    REDACT(R.string.tool_redact, R.string.tool_redact_sub, Glyph.Select),
    GRAYSCALE(R.string.tool_grayscale, R.string.tool_grayscale_sub, Glyph.Page),
    INVERT(R.string.tool_invert, R.string.tool_invert_sub, Glyph.Page),
    TEXT_TO_PDF(R.string.tool_text_to_pdf, R.string.tool_text_to_pdf_sub, Glyph.Page, ToolInput.BOOK),
    BOOKMARKS(R.string.tool_bookmarks, R.string.tool_bookmarks_sub, Glyph.Page),
    HEALTH(R.string.tool_health, R.string.tool_health_sub, Glyph.Page),
    BLANK_PDF(R.string.tool_blank_pdf, R.string.tool_blank_pdf_sub, Glyph.Add, ToolInput.NONE);

    /** Whether the tool lets the reader choose pages. */
    val usesPages: Boolean get() = this in setOf(EXTRACT, REMOVE, ROTATE, REORDER, PDF_TO_IMAGES)
}

/** Everything a tool can be asked, kept as the text the reader typed and read when the tool runs. */
data class ToolOptions(
    val pages: String = "",
    val every: String = "1",
    val degrees: Int = 90,
    val text: String = "CONFIDENTIAL",
    val opacity: Float = 0.25f,
    val size: String = "60",
    val start: String = "1",
    val quality: Float = 0.6f,
    val maxWidth: String = "1600",
    val password: String = "",
    val owner: String = "",
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val margin: String = "20",
    val dpi: String = "150",
    val jpeg: Boolean = false,
    val perSheet: Int = 4
)
